package com.kfincheck.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccountFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    private String register(String suffix) throws Exception {
        String id = "qa_" + suffix + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"TestOnly!2026\",\"nickname\":\"테스트계정\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        return id;
    }

    private MockHttpSession login(String id) throws Exception {
        var session = new MockHttpSession();
        String oldId = session.getId();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"TestOnly!2026\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.loginId").value(id));
        assertThat(session.getId()).isNotEqualTo(oldId);
        return session;
    }

    @Test
    void realDatabaseAccountsAreHashedAndWatchlistsAreIsolated() throws Exception {
        String first = register("a");
        String second = register("b");
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE login_id=?", String.class, first);
        assertThat(hash).startsWith("$2").isNotEqualTo("TestOnly!2026");
        var a = login(first); var b = login(second);
        mvc.perform(post("/api/watchlist/005930").session(a).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("005930"));
        mvc.perform(post("/api/watchlist/005930").session(a).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/watchlist").session(b)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/auth/me").session(a)).andExpect(status().isOk()).andExpect(jsonPath("$.loginId").value(first));
        mvc.perform(delete("/api/watchlist/005930").session(a).with(csrf())).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/auth/logout").session(a).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingCsrfDuplicateIdAndBadPassword() throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        String id = register("c");
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\""+id+"\",\"password\":\"TestOnly!2026\",\"nickname\":\"중복계정\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\""+id+"\",\"password\":\"Incorrect!2026\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/watchlist")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"bad\",\"password\":\"short\",\"nickname\":\"검증\"}"))
                .andExpect(status().isBadRequest());
    }
}
