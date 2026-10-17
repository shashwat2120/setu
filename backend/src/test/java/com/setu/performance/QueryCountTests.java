package com.setu.performance;

import com.setu.identity.infrastructure.security.JwtService;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import com.setu.testsupport.Day9TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * No N+1 queries (canonical spec section 13, performance): the number of SQL statements a list endpoint issues must not grow with the number of rows
 * it returns. Counts the statements the application prepares by wrapping the data source as it is created. Kept apart from the latency tests
 * ({@code DemoTargetsPerformanceTests}) because a wrapper around every JDBC call is exactly what a timing test should not carry.
 */
@Tag("performance")
@SpringBootTest
@AutoConfigureMockMvc
@Import(QueryCountTests.CountingDataSource.class)
class QueryCountTests {

    static final AtomicLong STATEMENTS = new AtomicLong();

    /** Counts every statement the application prepares, by wrapping the data source as it is created. */
    @TestConfiguration
    static class CountingDataSource {
        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource ds && !Proxy.isProxyClass(bean.getClass()) ? wrap(ds) : bean;
                }
            };
        }

        private static DataSource wrap(DataSource target) {
            InvocationHandler dataSource = (proxy, method, args) -> {
                Object result = invoke(target, method, args);
                return result instanceof Connection connection && "getConnection".equals(method.getName()) ? wrap(connection) : result;
            };
            return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, dataSource);
        }

        private static Connection wrap(Connection target) {
            Set<String> counted = Set.of("prepareStatement", "prepareCall", "createStatement");
            InvocationHandler connection = (proxy, method, args) -> {
                if (counted.contains(method.getName())) {
                    STATEMENTS.incrementAndGet();
                }
                return invoke(target, method, args);
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, connection);
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day9TestFixtures day9Fixtures;
    @Autowired
    JwtService jwtService;
    @Autowired
    MockMvc mockMvc;

    private record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID sectionId) {
    }

    private Scenario scenario(String prefix) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = fixtures.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8Fixtures.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = fixtures.provisionBuilding(tenant, prefix + "B");
        UUID room = fixtures.provisionRoom(tenant, building, "101", 60);
        UUID sectionId = day8Fixtures.provisionOpenSection(tenant, courseId, periodId, "S1", 50, room);
        return new Scenario(tenant, programId, curriculumId, sectionId);
    }

    private String adminBearer(Scenario s) {
        return "Bearer " + jwtService.issueAccessToken(s.tenant.adminUserId());
    }

    @Test
    void aListQueryCountDoesNotGrowWithTheSizeOfTheList() throws Exception {
        var s = scenario("PF4");
        String bearer = adminBearer(s);
        String list = "/enrollment/enrollments/section/" + s.sectionId + "?tenantId=" + s.tenant.tenantId() + "&size=50";
        for (int i = 0; i < 3; i++) {
            day9Fixtures.enrollStudent(day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "PF4A" + i), s.sectionId);
        }
        long small = statementsFor(list, bearer);

        for (int i = 0; i < 17; i++) {
            day9Fixtures.enrollStudent(day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "PF4B" + i), s.sectionId);
        }
        long large = statementsFor(list, bearer);

        System.out.printf("[perf] %-46s 3 rows: %d statements, 20 rows: %d statements%n", "SQL statements for the enrollment list", small, large);
        assertThat(large).as("statements for 20 rows (3 rows took %d): a list that issues one statement per row is an N+1", small)
            .isLessThanOrEqualTo(small + 1);
    }

    /** The statements one call issues, after a warm-up call so first-use work (metadata, lazy initialisation) is not counted. */
    private long statementsFor(String path, String bearer) throws Exception {
        mockMvc.perform(get(path).header("Authorization", bearer)).andReturn();
        long before = STATEMENTS.get();
        assertThat(mockMvc.perform(get(path).header("Authorization", bearer)).andReturn().getResponse().getStatus()).isEqualTo(200);
        return STATEMENTS.get() - before;
    }

}
