package com.connecthub.media.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(1)
public class TraceIdFilter implements Filter {
	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {
		String t = ((HttpServletRequest) req).getHeader("X-Trace-Id");
		if (t == null)
			t = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		MDC.put("traceId", t);
		try {
			chain.doFilter(req, resp);
		} finally {
			MDC.clear();
		}
	}
}
