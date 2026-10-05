// Initials avatar with a stable hue derived from the name — no image assets.
export default function Avatar({ name = '?', size = 36 }) {
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase()).join('') || '?'
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0
  const hue = hash % 360
  return (
    <div
      className="flex items-center justify-center rounded-full font-semibold text-white shrink-0 ring-1 ring-white/20"
      style={{
        width: size,
        height: size,
        fontSize: size * 0.38,
        background: `linear-gradient(135deg, hsl(${hue} 60% 42%), hsl(${(hue + 40) % 360} 65% 30%))`,
        boxShadow: `0 4px 14px hsl(${hue} 70% 40% / 0.35)`,
      }}
    >
      {initials}
    </div>
  )
}
