package com.setu.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** A page can never be larger than the ceiling, whatever the caller asks for; everything else about the request is left exactly as it was. */
class PageSizeLimitFilterTests {

    @RestController
    static class Echo {
        @GetMapping("/echo")
        String list(@RequestParam(defaultValue = "20") String size, @RequestParam(defaultValue = "0") int page) {
            return size + "/" + page;
        }

        @PostMapping("/echo")
        String create(@RequestParam(defaultValue = "20") String size) {
            return size;
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Echo()).addFilters(new PageSizeLimitFilter(100)).build();

    private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aPageLargerThanTheCeilingIsLoweredToIt() throws Exception {
        assertThat(body(get("/echo").param("size", "100000"))).isEqualTo("100/0");
        assertThat(body(get("/echo").param("size", "101"))).isEqualTo("100/0");
        assertThat(body(get("/echo").param("size", "99999999999999"))).as("larger than an int").isEqualTo("100/0");
    }

    @Test
    void everythingElseIsLeftAlone() throws Exception {
        assertThat(body(get("/echo").param("size", "100"))).as("exactly the ceiling").isEqualTo("100/0");
        assertThat(body(get("/echo").param("size", "5").param("page", "3"))).isEqualTo("5/3");
        assertThat(body(get("/echo"))).as("no size: the endpoint's own default").isEqualTo("20/0");
        assertThat(body(get("/echo").param("size", "abc"))).as("not a number: the endpoint decides").isEqualTo("abc/0");
        assertThat(body(get("/echo").param("size", "-4"))).as("not positive: the endpoint decides").isEqualTo("-4/0");
        assertThat(body(post("/echo").param("size", "100000"))).as("only GET lists are limited").isEqualTo("100000");
    }

    @Test
    void theCeilingIsConfigurableButNeverBelowOne() throws Exception {
        MockMvc small = MockMvcBuilders.standaloneSetup(new Echo()).addFilters(new PageSizeLimitFilter(10)).build();
        assertThat(small.perform(get("/echo").param("size", "50")).andReturn().getResponse().getContentAsString()).isEqualTo("10/0");
        MockMvc zero = MockMvcBuilders.standaloneSetup(new Echo()).addFilters(new PageSizeLimitFilter(0)).build();
        assertThat(zero.perform(get("/echo").param("size", "50")).andReturn().getResponse().getContentAsString()).isEqualTo("1/0");
    }
}
