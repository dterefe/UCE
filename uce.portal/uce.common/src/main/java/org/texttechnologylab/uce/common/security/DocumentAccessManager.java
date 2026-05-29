package org.texttechnologylab.uce.common.security;

import org.texttechnologylab.models.authentication.DocumentPermission;
import org.texttechnologylab.uce.common.exceptions.DatabaseOperationException;
import org.texttechnologylab.uce.common.exceptions.DocumentAccessDeniedException;
import org.texttechnologylab.uce.common.services.DataInterface;
import org.texttechnologylab.uce.common.utils.SystemStatus;

import java.util.Collection;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;

public class DocumentAccessManager {

    public static final String ADMIN_USERNAME = DocumentPermission.ADMIN_BYPASS_USERNAME;

    private final ThreadLocal<DocumentAccessContext> current = new ThreadLocal<>();
    private final DataInterface db;
    private static final DocumentAccessContext adminAccessContext = 
            new DocumentAccessContext(ADMIN_USERNAME, EnumSet.of(DocumentAccessContext.Role.ADMIN));

    public DocumentAccessManager(DataInterface db) {
        this.db = db;
    }

    public AutoCloseable asAdmin() {
        return as(adminAccessContext);
    }

    public AutoCloseable as(DocumentAccessContext context) {
        var previous = current.get();
        current.set(context);
        return () -> {
            if (previous == null) current.remove();
            else current.set(previous);
        };
    }

    public DocumentAccessContext current() {
        if (SystemStatus.UceConfig == null) {
            return adminAccessContext;
        }

        if (!SystemStatus.UceConfig.getSettings().getAuthentication().isActivated()) {
            return adminAccessContext;
        }

        var ctx = current.get();
        if (ctx == null) {
            throw new IllegalStateException("""
                    DocumentAccessContext is not set for thread %s while authentication is enabled. This happens when a code path bypasses DocumentAccessManager.as(...).
                    Common causes:
                    - HTTP handler not running inside the Javalin before/after guard in App.initSparkRoutes
                    - Background thread, CompletableFuture, or scheduler started without accessManager.wrap(...)/wrapAdmin(...) or an explicit try-with-resources guard
                    - CLI tool, test, or other out-of-band code invoking services without first calling accessManager.asAdmin()
                    Consult the authentication/authorization documentation for integration guidance.
                    """.formatted(Thread.currentThread().getName()));
        }
        return ctx;
    }

    public DocumentAccessContext admin() {
        return adminAccessContext;
    }

    public void checkAccess(long id,
                            DocumentPermission.DOCUMENT_PERMISSION_LEVEL level)
            throws DocumentAccessDeniedException, DatabaseOperationException {

        var ctx = current();

        if (ctx.hasRole(DocumentAccessContext.Role.ADMIN)) {
            return; // admin short-circuit
        }

        var cached = ctx.cachedPermission(id);
        if (cached != null && cached.ordinal() >= level.ordinal()) return;

        boolean allowed = db.hasDocumentAccess(ctx.principal(), id, level);
        if (allowed) {
            ctx.cachePermission(id, level);
            return;
        }

        throw new DocumentAccessDeniedException(ctx.principal(), id, level);
    }

    public void checkAccess(Collection<Long> documentIds,
                            DocumentPermission.DOCUMENT_PERMISSION_LEVEL level)
            throws DocumentAccessDeniedException, DatabaseOperationException {

        if (documentIds.isEmpty()) return;

        var ctx = current();
        
        if (ctx.hasRole(DocumentAccessContext.Role.ADMIN)) {
            return; // admin short-circuit
        }

        var missing = documentIds.stream()
                .filter(id -> {
                    var cached = ctx.cachedPermission(id);
                    return cached == null || cached.ordinal() < level.ordinal();
                })
                .toList();

        if (!missing.isEmpty()) {
            var results = db.hasDocumentAccess(ctx.principal(), missing, level);
            for (long id : missing) {
                if (Boolean.TRUE.equals(results.get(id))) {
                    ctx.cachePermission(id, level);
                } else {
                    throw new DocumentAccessDeniedException(ctx.principal(), id, level);
                }
            }
        }
    }

    public String permittedDocumentsExpression(DocumentPermission.DOCUMENT_PERMISSION_LEVEL level) {
        var ctx = current();

        return "permitted_documents(" + ctx.principal() + ", " + level.ordinal() + ")";
    }

    public String principal() {
        return current().principal();
    }

    public Runnable wrapAdmin(Runnable task) {
        return wrap(task, adminAccessContext);
    }

    public Runnable wrap(Runnable task) {
        return wrap(task, current());
    }

    public Runnable wrap(Runnable task, DocumentAccessContext context) {
        var ctx = context;
        return () -> {
            try (var guard = as(ctx)) {
                task.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    public CompletableFuture<Void> runAsyncAdmin(Runnable task) {
        return CompletableFuture.runAsync(wrap(task, adminAccessContext));
    }

    public CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(wrap(task, current()));
    }
}
