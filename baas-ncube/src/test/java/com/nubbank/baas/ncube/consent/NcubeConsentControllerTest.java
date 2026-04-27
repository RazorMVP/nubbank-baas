package com.nubbank.baas.ncube.consent;

import com.nubbank.baas.ncube.consent.dto.NubBankConsentDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcubeConsentController.class)
@Import({com.nubbank.baas.ncube.common.GlobalExceptionHandler.class,
         com.nubbank.baas.ncube.config.SecurityConfig.class})
class NcubeConsentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private NcubeConsentClient consentClient;

    @Test
    void getConsents_returnsCbnFormat() throws Exception {
        when(consentClient.getConsents(any())).thenReturn(List.of(
            new NubBankConsentDto("consent-uuid","AUTHORISED",
                List.of("ReadAccountsBasic","ReadBalances"),
                "partner-org-id","2026-12-31T00:00:00Z","2026-04-27T10:00:00Z")));

        mockMvc.perform(get("/baas/v1/ncube/consents").header("Authorization","Bearer jwt"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.Consent[0].ConsentId").value("consent-uuid"))
            .andExpect(jsonPath("$.Data.Consent[0].Status").value("Authorised"))
            .andExpect(jsonPath("$.Data.Consent[0].Permissions[0]").value("ReadAccountsBasic"));
    }

    @Test
    void createConsent_returnsCbnConsentCreated() throws Exception {
        when(consentClient.createConsent(any(), any())).thenReturn(
            new NubBankConsentDto("new-consent-uuid","AWAITING_AUTHORISATION",
                List.of("ReadAccountsBasic"),"partner-org-id",
                "2026-12-31T00:00:00Z","2026-04-27T10:00:00Z"));

        mockMvc.perform(post("/baas/v1/ncube/consents")
                .header("Authorization","Bearer jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"Data\":{\"Permissions\":[\"ReadAccountsBasic\"],\"ExpirationDateTime\":\"2026-12-31T00:00:00Z\"},\"Risk\":{}}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.Data.Consent.ConsentId").value("new-consent-uuid"))
            .andExpect(jsonPath("$.Data.Consent.Status").value("AwaitingAuthorisation"));
    }
}
