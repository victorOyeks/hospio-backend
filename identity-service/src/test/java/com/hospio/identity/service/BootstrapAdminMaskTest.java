package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The bootstrap super admin's address in the log: first letter and domain only. */
class BootstrapAdminMaskTest {

    @Test
    void masksTheLocalPart() {
        assertThat(BootstrapAdminService.mask("victor@oyeksglobal.com")).isEqualTo("v***@oyeksglobal.com");
        assertThat(BootstrapAdminService.mask("@x.ng")).isEqualTo("***");
        assertThat(BootstrapAdminService.mask(null)).isNull();
    }

    @Test
    void theIssueNeverPrintsItsPassword() {
        BootstrapAdminService.Issue issue = new BootstrapAdminService.Issue(BootstrapAdminService.Outcome.CREATED, "u1", "Hospio360 Admin",
                "victor@oyeksglobal.com", "Secret-Temp-Pass", null, 1, null);
        assertThat(issue.toString()).doesNotContain("Secret-Temp-Pass").contains("v***@oyeksglobal.com");
    }
}
