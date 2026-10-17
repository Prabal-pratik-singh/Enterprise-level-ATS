package com.ats.api;

import java.util.List;

import com.ats.taxonomy.SkillTaxonomy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The skill vocabulary, for the job form's autocomplete. */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillTaxonomy taxonomy;

    public SkillController(SkillTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    @GetMapping
    public List<String> all() {
        return taxonomy.canonicalNames();
    }
}
