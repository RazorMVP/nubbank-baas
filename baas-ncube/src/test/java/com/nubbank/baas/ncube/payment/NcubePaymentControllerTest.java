package com.nubbank.baas.ncube.payment;

import com.nubbank.baas.ncube.common.CbnMediaTypes;
import com.nubbank.baas.ncube.payment.dto.NipPaymentResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcubePaymentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({com.nubbank.baas.ncube.common.GlobalExceptionHandler.class,
         com.nubbank.baas.ncube.config.SecurityConfig.class})
class NcubePaymentControllerTest {

    private static final MediaType CBN_OB = MediaType.parseMediaType(CbnMediaTypes.CBN_OB_V1_JSON);

    @Autowired private MockMvc mockMvc;
    @MockBean private NipPaymentOrchestrator orchestrator;

    @Test
    void initiateNip_completedPayment_returns200() throws Exception {
        when(orchestrator.initiate(any(), any())).thenReturn(
            new NipPaymentResponse("pay-id-001", "COMPLETED", "e2e-ref-001", null));

        mockMvc.perform(post("/baas/v1/ncube/payments/nip")
                .header("Authorization", "Bearer test-jwt")
                .contentType(CBN_OB)
                .content("""
                    {"sourceAccountId":"src","destinationAccountNumber":"0581000099",
                     "destinationBankCode":"058","amount":5000.00,"currency":"NGN",
                     "narration":"Test","debtorBvn":"12345678901",
                     "debtorAccountTier":1,"debtorAccountDesignation":1,"channelCode":"1"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.Data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.Data.paymentId").value("pay-id-001"));
    }

    @Test
    void initiateNip_invalidBvn_returns400() throws Exception {
        mockMvc.perform(post("/baas/v1/ncube/payments/nip")
                .contentType(CBN_OB)
                .content("""
                    {"sourceAccountId":"src","destinationAccountNumber":"0581000099",
                     "destinationBankCode":"058","amount":5000.00,
                     "debtorBvn":"123","debtorAccountTier":1,"debtorAccountDesignation":1}"""))
            .andExpect(status().isBadRequest());
    }
}
