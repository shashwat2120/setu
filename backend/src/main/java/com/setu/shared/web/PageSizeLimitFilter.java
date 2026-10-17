package com.setu.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * No list endpoint can be asked for more than {@code app.pagination.max-size} rows (default 100) in one page. Paged endpoints read their page size
 * from the {@code size} query parameter, and without a ceiling {@code ?size=10000000} loads a whole table into memory in one request, which is both a
 * denial-of-service lever and the opposite of "paginate large sets" (canonical spec section 14). The ceiling is applied once, here, to every service
 * that serves lists, instead of being remembered in each controller: a larger {@code size} is quietly lowered to the ceiling and the response says
 * the size it really used.
 * <p>
 * Registered explicitly by {@link PageSizeLimitFilterConfig}, not scanned as a component: a scanned filter in the shared module is wrapped in
 * Spring Modulith's tracing proxy, which breaks the servlet container's initialisation of it (the same reason as {@link CorrelationIdFilter}).
 * <p>
 * Only {@code size} on GET requests is touched. A value that is not a number, or is below one, is left alone for the endpoint to reject as it always has.
 */
public class PageSizeLimitFilter extends OncePerRequestFilter {

    private final int maxSize;

    public PageSizeLimitFilter(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || request.getParameter("size") == null;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String[] sizes = request.getParameterValues("size");
        boolean tooLarge = false;
        String[] limited = new String[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            limited[i] = sizes[i];
            try {
                if (Long.parseLong(sizes[i].trim()) > maxSize) {
                    limited[i] = Integer.toString(maxSize);
                    tooLarge = true;
                }
            } catch (NumberFormatException e) {
                // not a number: the endpoint rejects it, as before
            }
        }
        filterChain.doFilter(tooLarge ? new Limited(request, limited) : request, response);
    }

    /** The same request with a lower {@code size}. */
    private static final class Limited extends HttpServletRequestWrapper {
        private final String[] size;

        Limited(HttpServletRequest request, String[] size) {
            super(request);
            this.size = size;
        }

        @Override
        public String getParameter(String name) {
            return "size".equals(name) ? size[0] : super.getParameter(name);
        }

        @Override
        public String[] getParameterValues(String name) {
            return "size".equals(name) ? size.clone() : super.getParameterValues(name);
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> all = new LinkedHashMap<>(super.getParameterMap());
            all.put("size", size.clone());
            return all;
        }
    }
}
