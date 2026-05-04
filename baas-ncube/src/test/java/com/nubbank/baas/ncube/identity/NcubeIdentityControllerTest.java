package com.nubbank.baas.ncube.identity;

import com.nubbank.baas.ncube.common.CbnMediaTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcubeIdentityController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({com.nubbank.baas.ncube.config.SecurityConfig.class,
         com.nubbank.baas.ncube.common.GlobalExceptionHandler.class})
class NcubeIdentityControllerTest {

    private static final MediaType CBN_OB = MediaType.parseMediaType(CbnMediaTypes.CBN_OB_V1_JSON);

    @Autowired private MockMvc mockMvc;

    @Test
    void verifyBvn_validFormat_returnsStubVerified() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/identity/verify-bvn")
                .header("Authorization", "Bearer test-jwt")
                .contentType(CBN_OB)
                .content("{\"bvn\":\"12345678901\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.identifier").value("00000000000"))
            .andExpect(jsonPath("$.data.verified").value(true))
            .andExpect(jsonPath("$.data.verificationSource").value("NIBSS_NCUBE_STUB"));
    }

    @Test
    void verifyBvn_tooShort_returns400() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/identity/verify-bvn")
                .contentType(CBN_OB)
                .content("{\"bvn\":\"123\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void verifyBvn_nonNumeric_returns400() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/identity/verify-bvn")
                .contentType(CBN_OB)
                .content("{\"bvn\":\"ABCDE678901\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void verifyNin_validFormat_returnsStubVerified() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/identity/verify-nin")
                .header("Authorization", "Bearer test-jwt")
                .contentType(CBN_OB)
                .content("{\"nin\":\"98765432109\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.identifier").value("00000000000"))
            .andExpect(jsonPath("$.data.verified").value(true))
            .andExpect(jsonPath("$.data.verificationSource").value("NIBSS_NCUBE_STUB"));
    }
}
