package app.cut;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class LocalAccess extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
            + "connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (request.getRequestURI().startsWith("/api/")) response.setHeader("Cache-Control", "no-store");
        if (!HOSTS.contains(request.getServerName().toLowerCase(Locale.ROOT)) || !allowedOrigin(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowedOrigin(HttpServletRequest request) {
        if (request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) return true;
        var origin = request.getHeader("Origin");
        if (origin == null) return true;
        try {
            var uri = URI.create(origin);
            return "http".equals(uri.getScheme()) && uri.getHost() != null &&
                HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) && uri.getPort() == request.getLocalPort()
                && uri.getUserInfo() == null && (uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
