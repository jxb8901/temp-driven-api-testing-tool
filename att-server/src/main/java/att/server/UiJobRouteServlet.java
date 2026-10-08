package att.server;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Keeps deep links to a job usable while the browser UI remains a static client.
 * The redirect carries the job ID into the UI's canonical hash route.
 */
@WebServlet(urlPatterns = "/ui/jobs/*")
public final class UiJobRouteServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || !pathInfo.matches("/[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String jobId = pathInfo.substring(1);
        response.sendRedirect(request.getContextPath() + "/ui/index.html#/jobs/" + jobId);
    }
}
