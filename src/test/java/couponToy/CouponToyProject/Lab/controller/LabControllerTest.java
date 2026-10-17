package couponToy.CouponToyProject.Lab.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import couponToy.CouponToyProject.CouponIssue.service.CouponIssueStrategy;
import couponToy.CouponToyProject.CouponIssue.support.AbstractCouponIssueIntegrationTestSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LabControllerTest extends AbstractCouponIssueIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("준비 → 발급 → 결과 조회 → 초기화")
    void labFlow() throws Exception {
        JsonNode members = body(postJson("/lab/members", "{\"count\": 3}").andExpect(status().isCreated()));
        long first = members.path("response").path("firstMemberId").asLong();

        JsonNode coupon = body(postJson("/lab/coupons", "{\"name\": \"lab\", \"totalCount\": 1}").andExpect(status().isCreated()));
        long couponId = coupon.path("response").path("couponId").asLong();
        String issuePath = "/lab/coupons/" + couponId + "/issue";

        postJson(issuePath, issueBody(first, "SERIALIZABLE")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.response.memberId").value(first));
        postJson(issuePath, issueBody(first, "SERIALIZABLE")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("이미 쿠폰 발급 받은 사용자입니다."));
        postJson(issuePath, issueBody(first + 1, "REPEATABLE_READ")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("쿠폰이 모두 소진되었습니다."));
        postJson(issuePath, issueBody(999_999, "SERIALIZABLE")).andExpect(status().isNotFound());

        mockMvc.perform(get("/lab/coupons/{couponId}/result", couponId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.issueRows").value(1))
                .andExpect(jsonPath("$.response.issuedCount").value(1))
                .andExpect(jsonPath("$.response.checks.notOverIssued").value(true))
                .andExpect(jsonPath("$.response.checks.issuedCountMatchesRows").value(true))
                .andExpect(jsonPath("$.response.requests.SUCCESS").value(1))
                .andExpect(jsonPath("$.response.requests.DUPLICATE").value(1))
                .andExpect(jsonPath("$.response.requests.SOLD_OUT").value(1))
                .andExpect(jsonPath("$.response.requests.NOT_FOUND").value(1))
                .andExpect(jsonPath("$.response.ordering.validRequests").value(2));

        mockMvc.perform(delete("/lab/data")).andExpect(status().isOk());
        mockMvc.perform(get("/lab/coupons/{couponId}/result", couponId)).andExpect(status().isNotFound());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CouponIssueStrategy.class)
    @DisplayName("모든 방식이 같은 성공·중복·품절 응답 형식을 쓴다")
    void responseContractPerMethod(CouponIssueStrategy method) throws Exception {
        long first = body(postJson("/lab/members", "{\"count\": 2}")).path("response").path("firstMemberId").asLong();
        long couponId = body(postJson("/lab/coupons", "{\"name\": \"contract\", \"totalCount\": 1}"))
                .path("response").path("couponId").asLong();
        String issuePath = "/lab/coupons/" + couponId + "/issue";

        // 비동기 방식은 접수만 됐으므로 202
        postJson(issuePath, issueBody(first, method.name()))
                .andExpect(status().is(method == CouponIssueStrategy.REDIS_EVENT ? 202 : 201))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.response.memberId").value(first))
                .andExpect(jsonPath("$.response.couponId").value(couponId))
                .andExpect(jsonPath("$.error").value(Matchers.nullValue()));
        postJson(issuePath, issueBody(first, method.name()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.response").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.error.message").value("이미 쿠폰 발급 받은 사용자입니다."))
                .andExpect(jsonPath("$.error.status").value(409));
        postJson(issuePath, issueBody(first + 1, method.name()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("쿠폰이 모두 소진되었습니다."))
                .andExpect(jsonPath("$.error.status").value(409));
    }

    @Test
    @DisplayName("잘못된 입력은 400")
    void invalidInput() throws Exception {
        postJson("/lab/coupons/1/issue", "{\"memberId\": 1, \"method\": \"NO_SUCH_METHOD\"}").andExpect(status().isBadRequest());
        postJson("/lab/coupons/1/issue", "{\"method\": \"SERIALIZABLE\"}").andExpect(status().isBadRequest());
        postJson("/lab/coupons/abc/issue", issueBody(1, "SERIALIZABLE")).andExpect(status().isBadRequest());
        postJson("/lab/members", "{\"count\": 0}").andExpect(status().isBadRequest());
        postJson("/lab/coupons", "{\"name\": \"lab\", \"totalCount\": -1}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는 경로는 404 (삭제한 이전 엔드포인트 포함)")
    void unknownPathIsNotFound() throws Exception {
        postJson("/members/login", "{}").andExpect(status().isNotFound());
        postJson("/coupons/issue/1", "{}").andExpect(status().isNotFound());
    }

    private ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private static String issueBody(long memberId, String method) {
        return "{\"memberId\": " + memberId + ", \"method\": \"" + method + "\"}";
    }
}
