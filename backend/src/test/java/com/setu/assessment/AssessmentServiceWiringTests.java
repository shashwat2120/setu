package com.setu.assessment;

import com.setu.testsupport.ExtractedServiceWiringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The Assessment service on its own: see {@link ExtractedServiceWiringSupport} for what is checked. */
@SpringBootTest(classes = AssessmentServiceApplication.class, properties = {"setu.service=assessment", "setu.auth.mode=remote", "app.readmodel.schema=assessment",
    "app.readmodel.consumer.enabled=false", "app.realtime.enabled=false"})
@AutoConfigureMockMvc
@Import(ExtractedServiceWiringSupport.IdentityKeys.class)
class AssessmentServiceWiringTests extends ExtractedServiceWiringSupport {

    @Override
    protected String schema() {
        return "assessment";
    }

    @Override
    protected String module() {
        return "assessment";
    }

    @Override
    protected MockHttpServletRequestBuilder sectionRead(UUID sectionId, UUID tenantId) {
        return get("/assessment/assessments").param("sectionId", sectionId.toString()).param("tenantId", tenantId.toString());
    }

    @Override
    protected boolean enrolledStudentMayRead() {
        return true;
    }
}
