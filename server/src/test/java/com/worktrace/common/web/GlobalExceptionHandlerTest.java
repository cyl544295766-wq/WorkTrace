package com.worktrace.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.worktrace.common.api.Result;
import com.worktrace.common.api.ResultCode;
import com.worktrace.common.exception.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(handler)
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void bizExceptionMapsToHttpStatusAndUnifiedBody() throws Exception {
        String traceId = mockMvc.perform(get("/test/biz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("资源不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.ts").isNumber())
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn().getResponse().getHeader("X-Trace-Id");

        assertThat(traceId).matches("^[0-9a-f]{12}$");
    }

    @Test
    void validationFailureMapsTo400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.data.errors[0].field").value("name"))
                .andExpect(jsonPath("$.data.errors[0].message").value("名称不能为空"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void unknownExceptionMapsTo500WithoutLeakingDetails() throws Exception {
        String body = mockMvc.perform(get("/test/error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(90001))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("boom").doesNotContain("IllegalStateException");
    }

    @Test
    void malformedJsonMapsTo10002() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void unsupportedMethodMapsTo10004() throws Exception {
        mockMvc.perform(post("/test/biz"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(10004));
    }

    @Test
    void unknownRouteMapsTo10003() {
        ResponseEntity<Result<Object>> response = handler.handleNotFound(
                new NoHandlerFoundException("GET", "/api/unknown/path", new org.springframework.http.HttpHeaders()));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ResultCode.RESOURCE_NOT_FOUND.getCode());
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        @GetMapping("/biz")
        public Result<Void> biz() {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND);
        }

        @PostMapping("/validate")
        public Result<Void> validate(@Valid @RequestBody TestReq req) {
            return Result.ok();
        }

        @GetMapping("/error")
        public Result<Void> error() {
            throw new IllegalStateException("boom: internal detail must not leak");
        }
    }

    static class TestReq {
        @NotBlank(message = "名称不能为空")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
