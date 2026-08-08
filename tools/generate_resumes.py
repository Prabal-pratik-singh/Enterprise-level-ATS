#!/usr/bin/env python3
"""Synthetic resume generator for the ATS pipeline demo.

Runs inside python:3.12-slim via tools/seed.sh (needs reportlab + pillow);
no host Python required. Deterministic for a given --seed.

Output: <out>/resume_NNN[_kind].pdf + <out>/manifest.tsv with columns
    filename <TAB> name <TAB> email <TAB> phone <TAB> kind
kinds: text | image | corrupt | fraud_overlap | fraud_timeline | fraud_hidden

Mix rules (documented in PROGRESS.md):
  - N >= 10: include 1 corrupt file + the 3 planted-fraud resumes
  - N >= 5:  ~18% of the normal resumes are image-only PDFs (OCR targets)
"""
import argparse
import io
import os
import random
import textwrap

import reportlab
from reportlab.lib.pagesizes import LETTER
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas as pdfcanvas
from PIL import Image, ImageDraw, ImageFont

# Fixed "current month" keeps output deterministic regardless of run date.
NOW_Y, NOW_M = 2026, 8

FIRST = ["Aarav", "Vivaan", "Aditya", "Ishaan", "Kabir", "Ananya", "Diya", "Priya", "Sneha", "Riya",
         "Rahul", "Rohan", "Arjun", "Karan", "Nikhil", "Pooja", "Neha", "Kavya", "Meera", "Tanvi",
         "Siddharth", "Varun", "Amit", "Deepak", "Sanjay", "Divya", "Shreya", "Aisha", "Lakshmi", "Ritu"]
LAST = ["Sharma", "Verma", "Gupta", "Mehta", "Iyer", "Nair", "Reddy", "Rao", "Das", "Bose",
        "Kulkarni", "Joshi", "Malhotra", "Kapoor", "Chopra", "Banerjee", "Mukherjee", "Patel", "Shah", "Jain",
        "Agarwal", "Sinha", "Mishra", "Pandey", "Tiwari", "Singh", "Yadav", "Chauhan", "Bhatt", "Menon"]
DOMAINS = ["gmail.com", "outlook.com", "yahoo.com", "protonmail.com"]
DISPOSABLE_DOMAIN = "mailinator.com"  # planted for the Phase 6 disposable-email heuristic

BACKEND_SKILLS = ["java", "spring-boot", "kafka", "sql", "postgresql", "docker", "aws", "kubernetes",
                  "redis", "rabbitmq", "microservices", "rest-api", "git", "maven", "jenkins", "grafana"]
GENERAL_SKILLS = ["python", "react", "node.js", "mongodb", "typescript", "html", "css", "graphql",
                  "angular", "flask", "django", "express"]
COMPANIES = ["TechNova Solutions", "CloudSprint", "DataWeave Labs", "Finlytics", "OrbitPay",
             "NexCore Systems", "BlueShift Analytics", "Quantia", "MapleTech", "ZenStack",
             "Hyperloop Retail", "Veritas Health"]
TITLES = ["Software Engineer", "Backend Developer", "Senior Software Engineer",
          "Java Developer", "Platform Engineer", "Software Developer"]
CITIES = ["Bengaluru", "Pune", "Hyderabad", "Gurugram", "Chennai", "Noida", "Mumbai"]
DEGREES = [("B.Tech Computer Science", "IIT Delhi"), ("B.E. Information Technology", "BITS Pilani"),
           ("B.Tech CSE", "NIT Trichy"), ("BCA", "Delhi University"), ("M.Tech CSE", "IIT Bombay"),
           ("B.Tech CSE", "VIT Vellore")]
CERTS = ["AWS Certified Developer - Associate", "Oracle Certified Professional: Java SE",
         "Confluent Certified Developer for Apache Kafka", "CKA: Certified Kubernetes Administrator",
         "Microsoft Certified: Azure Fundamentals"]
BULLETS = [
    "Built and operated {a}-based microservices handling {n}K requests/day, backed by {b}.",
    "Designed event-driven data flows with {a} and {b}, cutting batch latency by {p}%.",
    "Led migration of legacy modules to {a} running on {b}, tripling deploy frequency.",
    "Implemented CI/CD pipelines with {a}; containerized services using {b}.",
    "Optimized {a} queries and {b}-based caching, reducing p95 latency by {p}%.",
    "Owned on-call for {a} services; wrote runbooks and dashboards with {b}.",
]
PROJECT_TEMPLATES = [
    ("Realtime Order Tracker", "Streamed order status events through {a} into a {b} read model consumed by dashboards."),
    ("Log Analytics Pipeline", "Parsed multi-GB application logs with {a} and indexed aggregates into {b}."),
    ("Payment Reconciliation Service", "Matched ledger entries across providers using {a}, persisted in {b}."),
    ("Inventory Sync Engine", "Idempotent {a} consumers syncing inventory to {b} across 40 stores."),
]
MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]


def ym_fmt(total_months):
    y, m = divmod(total_months, 12)
    return f"{MONTHS[m]} {y}"


def make_person(rng, i, domain=None):
    first, last = rng.choice(FIRST), rng.choice(LAST)
    dom = domain or rng.choice(DOMAINS)
    email = f"{first.lower()}.{last.lower()}{rng.randint(100, 999)}{i}@{dom}"
    phone = "+91-9" + "".join(str(rng.randint(0, 9)) for _ in range(9))
    return f"{first} {last}", email, phone


def pick_skills(rng):
    n_back = rng.randint(4, 9)
    n_gen = rng.randint(1, 4)
    skills = rng.sample(BACKEND_SKILLS, n_back) + rng.sample(GENERAL_SKILLS, n_gen)
    rng.shuffle(skills)
    return skills


def make_career(rng, grad_year=None, roles_spec=None):
    """Returns (grad_year, degree, school, roles); roles = [(title, company, start_tm, end_tm|None)]
    where *_tm are total-month integers (year*12 + month0)."""
    grad_year = grad_year or rng.randint(2014, 2022)
    degree, school = rng.choice(DEGREES)
    now_tm = NOW_Y * 12 + (NOW_M - 1)
    if roles_spec is not None:
        return grad_year, degree, school, roles_spec
    roles = []
    t = grad_year * 12 + rng.randint(5, 8)
    while t < now_tm - 8 and len(roles) < 4:
        dur = rng.randint(14, 38)
        end = min(t + dur, now_tm)
        current = end >= now_tm - 1
        roles.append((rng.choice(TITLES), rng.choice(COMPANIES), t, None if current else end))
        if current:
            break
        t = end + rng.randint(0, 3)
    if not roles:  # very recent grad — one short current role
        roles.append((rng.choice(TITLES), rng.choice(COMPANIES), t, None))
    return grad_year, degree, school, roles


def resume_lines(rng, name, email, phone, skills, career, summary=None):
    grad_year, degree, school, roles = career
    now_tm = NOW_Y * 12 + (NOW_M - 1)
    first_start = min(r[2] for r in roles)
    years = max(1, round((now_tm - first_start) / 12))
    a, b = (skills + ["java", "sql"])[0], (skills + ["java", "sql"])[1]
    summary = summary or (f"{roles[-1][0]} with {years} years of experience building {a} and {b} "
                          f"systems. Comfortable owning services end to end, from data model to deployment.")
    lines = [("h1", name),
             ("small", f"{email}  |  {phone}  |  {rng.choice(CITIES)}, India"),
             ("h2", "SUMMARY"), ("body", summary),
             ("h2", "SKILLS"), ("body", ", ".join(skills)),
             ("h2", "EXPERIENCE")]
    for title, company, start, end in reversed(roles):
        period = f"{ym_fmt(start)} - {'Present' if end is None else ym_fmt(end)}"
        lines.append(("h3", f"{title} — {company}  ({period}, Full-time)"))
        for _ in range(rng.randint(2, 3)):
            s1, s2 = rng.sample(skills, 2) if len(skills) >= 2 else ("java", "sql")
            lines.append(("bullet", "- " + rng.choice(BULLETS).format(
                a=s1, b=s2, n=rng.choice([20, 50, 120, 400]), p=rng.choice([18, 25, 30, 40, 60]))))
    lines += [("h2", "EDUCATION"), ("body", f"{degree}, {school} — graduated {grad_year}")]
    pname, pdesc = rng.choice(PROJECT_TEMPLATES)
    s1, s2 = rng.sample(skills, 2) if len(skills) >= 2 else ("kafka", "postgresql")
    lines += [("h2", "PROJECTS"), ("h3", pname), ("bullet", "- " + pdesc.format(a=s1, b=s2))]
    if rng.random() < 0.6:
        lines += [("h2", "CERTIFICATIONS")]
        for cert in rng.sample(CERTS, rng.randint(1, 2)):
            lines.append(("bullet", "- " + cert))
    return lines


# ---------------------------------------------------------------- PDF writers

VARIANTS = [  # (bold_font, body_font, margin, body_size)
    ("Helvetica-Bold", "Helvetica", 54, 9.5),
    ("Times-Bold", "Times-Roman", 64, 10.0),
    ("Helvetica-Bold", "Courier", 50, 8.5),
]


def write_text_pdf(path, lines, variant, hidden_text=None):
    bold, body_font, margin, body_size = VARIANTS[variant % len(VARIANTS)]
    c = pdfcanvas.Canvas(path, pagesize=LETTER)
    width, height = LETTER
    y = height - margin

    def emit(text, font, size, gap_after=0.0, wrap=100):
        nonlocal y
        for line in textwrap.wrap(text, width=wrap) or [""]:
            if y < margin + 20:
                c.showPage()
                y = height - margin
            c.setFillColorRGB(0, 0, 0)
            c.setFont(font, size)
            c.drawString(margin, y, line)
            y -= size * 1.45
        y -= gap_after

    for style, text in lines:
        if style == "h1":
            emit(text, bold, 17, gap_after=2)
        elif style == "h2":
            y -= 6
            emit(text.upper(), bold, 11, gap_after=2)
        elif style == "h3":
            emit(text, bold, body_size + 0.5)
        elif style == "small":
            emit(text, body_font, 8.5, gap_after=4)
        else:
            emit(text, body_font, body_size, wrap=105)

    if hidden_text:
        # Planted fraud: white 1pt keyword stuffing — invisible to a human,
        # captured by the parser's font/color metadata in Phase 3.
        c.setFillColorRGB(1, 1, 1)
        c.setFont("Helvetica", 1)
        c.drawString(margin, 16, hidden_text)
    c.save()


def write_image_pdf(path, lines):
    """Image-only PDF (no text layer) — forces the OCR fallback path."""
    W, H = 1275, 1650  # letter at 150 dpi
    img = Image.new("RGB", (W, H), "white")
    draw = ImageDraw.Draw(img)
    vera = os.path.join(os.path.dirname(reportlab.__file__), "fonts", "Vera.ttf")
    fonts = {"h1": ImageFont.truetype(vera, 44), "h2": ImageFont.truetype(vera, 30),
             "h3": ImageFont.truetype(vera, 26), "body": ImageFont.truetype(vera, 24),
             "bullet": ImageFont.truetype(vera, 24), "small": ImageFont.truetype(vera, 22)}
    y = 80
    for style, text in lines:
        f = fonts.get(style, fonts["body"])
        if style == "h2":
            y += 14
        for line in textwrap.wrap(text, width=78) or [""]:
            if y > H - 90:
                break
            draw.text((90, y), line, fill="black", font=f)
            y += int(f.size * 1.5)
    buf = io.BytesIO()
    img.save(buf, "PNG")
    buf.seek(0)
    c = pdfcanvas.Canvas(path, pagesize=LETTER)
    c.drawImage(ImageReader(buf), 0, 0, width=LETTER[0], height=LETTER[1])
    c.save()


def write_corrupt_pdf(path, rng):
    """Valid %PDF magic bytes (passes upload validation by design) but garbage
    body — must fail parsing and land in resume.uploaded.dlq in Phase 3."""
    with open(path, "wb") as f:
        f.write(b"%PDF-1.4\n")
        f.write(rng.randbytes(4096))


# ---------------------------------------------------------------- fraud cases

def fraud_overlap(rng, name, email, phone):
    """Two overlapping full-time roles."""
    skills = pick_skills(rng)
    tm = lambda y, m: y * 12 + (m - 1)
    roles = [
        (rng.choice(TITLES), "TechNova Solutions", tm(2018, 7), tm(2021, 2)),
        ("Senior Software Engineer", "CloudSprint", tm(2021, 3), tm(2024, 6)),
        ("Backend Developer", "OrbitPay", tm(2022, 1), tm(2025, 5)),  # overlaps previous role
    ]
    career = make_career(rng, grad_year=2018, roles_spec=roles)
    return resume_lines(rng, name, email, phone, skills, career)


def fraud_timeline(rng, name, email, phone):
    """Claims 8+ years of Kubernetes but graduated in 2019 (max ~7 possible)."""
    skills = ["kubernetes", "java", "spring-boot", "kafka", "docker", "aws", "sql"]
    tm = lambda y, m: y * 12 + (m - 1)
    roles = [
        ("Platform Engineer", "NexCore Systems", tm(2019, 8), tm(2022, 12)),
        ("Senior Software Engineer", "BlueShift Analytics", tm(2023, 1), None),
    ]
    career = make_career(rng, grad_year=2019, roles_spec=roles)
    summary = ("Platform engineer with 8+ years of hands-on production Kubernetes experience, "
               "running large multi-tenant clusters and Kafka-based platforms at scale.")
    return resume_lines(rng, name, email, phone, skills, career, summary=summary)


HIDDEN_STUFFING = ("kubernetes kafka aws java spring-boot terraform microservices distributed-systems "
                   "machine-learning sql docker react leadership architecture ") * 3


# ------------------------------------------------------------------------ main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, required=True)
    ap.add_argument("--seed", type=int, required=True)
    ap.add_argument("--out", default="out")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    os.makedirs(args.out, exist_ok=True)
    n = args.count

    include_special = n >= 10
    kinds = []
    if include_special:
        kinds += ["fraud_overlap", "fraud_timeline", "fraud_hidden", "corrupt"]
    n_normal = n - len(kinds)
    if n_normal < 0:
        raise SystemExit("--count must be >= 4 when specials are included (N >= 10)")
    n_image = max(1, round(n_normal * 0.18)) if n >= 5 and n_normal > 0 else 0
    kinds += ["image"] * n_image + ["text"] * (n_normal - n_image)
    rng.shuffle(kinds)

    manifest = []
    for i, kind in enumerate(kinds, 1):
        domain = DISPOSABLE_DOMAIN if kind == "fraud_timeline" else None
        name, email, phone = make_person(rng, i, domain=domain)
        filename = f"resume_{i:03d}_{kind}.pdf"
        path = os.path.join(args.out, filename)

        if kind == "corrupt":
            write_corrupt_pdf(path, rng)
        elif kind == "image":
            lines = resume_lines(rng, name, email, phone, pick_skills(rng), make_career(rng))
            write_image_pdf(path, lines)
        elif kind == "fraud_overlap":
            write_text_pdf(path, fraud_overlap(rng, name, email, phone), rng.randrange(3))
        elif kind == "fraud_timeline":
            write_text_pdf(path, fraud_timeline(rng, name, email, phone), rng.randrange(3))
        elif kind == "fraud_hidden":
            lines = resume_lines(rng, name, email, phone, pick_skills(rng), make_career(rng))
            write_text_pdf(path, lines, rng.randrange(3), hidden_text=HIDDEN_STUFFING)
        else:
            lines = resume_lines(rng, name, email, phone, pick_skills(rng), make_career(rng))
            write_text_pdf(path, lines, rng.randrange(3))

        manifest.append("\t".join([filename, name, email, phone, kind]))

    with open(os.path.join(args.out, "manifest.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(manifest) + "\n")

    counts = {}
    for k in kinds:
        counts[k] = counts.get(k, 0) + 1
    print(f"generated {n} resumes (seed={args.seed}) into {args.out}: " +
          ", ".join(f"{v} {k}" for k, v in sorted(counts.items())))


if __name__ == "__main__":
    main()
