package com.setu.learning;

import com.setu.testsupport.ExtractedServiceWiringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The Learning service on its own: see {@link ExtractedServiceWiringSupport} for what is checked. */
@SpringBootTest(classes = LearningServiceApplication.class, properties = {"setu.service=learning", "setu.auth.mode=remote", "app.readmodel.schema=learning",
    "app.readmodel.consumer.enabled=false", "app.realtime.enabled=false"})
@AutoConfigureMockMvc
@Import(ExtractedServiceWiringSupport.IdentityKeys.class)
class LearningServiceWiringTests extends ExtractedServiceWiringSupport {

    @Override
    protected String schema() {
        return "learning";
    }

    @Override
    protected String module() {
        return "learning";
    }

    @Override
    protected MockHttpServletRequestBuilder sectionRead(UUID sectionId, UUID tenantId) {
        return get("/learning/modules").param("sectionId", sectionId.toString()).param("tenantId", tenantId.toString());
    }

    @Override
    protected boolean enrolledStudentMayRead() {
        return true;
    }
}
