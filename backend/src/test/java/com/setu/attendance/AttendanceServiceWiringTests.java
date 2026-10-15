package com.setu.attendance;

import com.setu.testsupport.ExtractedServiceWiringSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The Attendance service on its own: see {@link ExtractedServiceWiringSupport} for what is checked. */
@SpringBootTest(classes = AttendanceServiceApplication.class, properties = {"setu.service=attendance", "setu.auth.mode=remote", "app.readmodel.schema=attendance",
    "app.readmodel.consumer.enabled=false"})
@AutoConfigureMockMvc
@Import(ExtractedServiceWiringSupport.IdentityKeys.class)
class AttendanceServiceWiringTests extends ExtractedServiceWiringSupport {

    @Override
    protected String schema() {
        return "attendance";
    }

    @Override
    protected String module() {
        return "attendance";
    }

    @Override
    protected MockHttpServletRequestBuilder sectionRead(UUID sectionId, UUID tenantId) {
        return get("/attendance/sessions").param("sectionId", sectionId.toString());
    }

    @Override
    protected boolean enrolledStudentMayRead() {
        return false;
    }
}
