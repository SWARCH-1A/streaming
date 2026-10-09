package streaming.core.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ContentNegotiationTest {
    @RestController static class ImageEndpoint {
        @GetMapping(path="/image",produces="image/jpeg")
        byte[] image() { return new byte[]{1,2,3}; }
    }
    @Test void incompatibleImageAcceptIsClientErrorInsteadOfServerFailure() throws Exception {
        var mvc=standaloneSetup(new ImageEndpoint()).setControllerAdvice(new CoreErrorHandler()).build();
        mvc.perform(get("/image").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
        mvc.perform(get("/image").accept(MediaType.IMAGE_JPEG))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1,2,3}));
    }
}
