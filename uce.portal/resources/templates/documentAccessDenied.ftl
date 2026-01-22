<head>
    <link rel="stylesheet"
          href="https://cdn.jsdelivr.net/npm/bootstrap@4.3.1/dist/css/bootstrap.min.css"
          integrity="sha384-ggOyR0iXCbMQv3Xipma34MD+dH/1fQ784/j6cY/iJTQUOhcWr7x9JvoRxT2MZw1T"
          crossorigin="anonymous">
    <style>
        <#include "css/site.css">

        .document-access-denied-screen {
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
            background: rgb(245, 245, 247);
            padding: 2rem;
        }

        .document-access-denied-card {
            background: #ffffff;
            border-radius: 0;
            box-shadow: 0 20px 35px rgba(0, 0, 0, 0.15);
            max-width: 560px;
            width: 100%;
            padding: 2.5rem;
            text-align: center;
        }

        .document-access-denied-icon {
            width: 90px;
            height: 90px;
            border-radius: 0;
            background: rgba(230, 57, 70, 0.1);
            display: flex;
            align-items: center;
            justify-content: center;
            margin: 0 auto 1.5rem;
            font-size: 2.5rem;
            color: #e63946;
        }

        .document-access-denied-card h1 {
            font-size: 1.75rem;
            color: #1d3557;
            margin-bottom: 0.75rem;
        }

        .document-access-denied-card p {
            color: #4a4a4a;
            margin-bottom: 0.5rem;
        }

        .document-access-denied-request-id {
            font-size: 0.85rem;
            color: #7a7a7a;
            margin-top: 1rem;
        }

        .document-access-denied-actions a {
            margin: 0 0.5rem;
        }
    </style>
</head>

<section class="document-access-denied-screen">
    <div class="document-access-denied-card">
        <div class="document-access-denied-icon">
            <span>🔒</span>
        </div>

        <#assign hasLanguage = languageResource??>

        <#if hasLanguage>
            <#assign noAccessText    = languageResource.get("noAccess")!"Access denied">
            <#assign backHomeText   = languageResource.get("backToHome")!"Back to home">
            <#assign goBackText     = languageResource.get("goBack")!"Go back">
            <#assign contactAdmin   = languageResource.get("contactAdminText")!
                "Please contact an administrator if you believe this is an error.">
        <#else>
            <#assign noAccessText  = "Access denied">
            <#assign backHomeText  = "Back to home">
            <#assign goBackText    = "Go back">
            <#assign contactAdmin  = "Please contact an administrator if you believe this is an error.">
        </#if>

        <h1>${noAccessText}</h1>
        <p class="mb-2">
            <#if information??>
                ${information}
            <#else>
                You do not have permission to access this resource.
            </#if>
        </p>
        <p>${contactAdmin}</p>
        <#if requestId??>
            <p class="document-access-denied-request-id">Request ID: ${requestId}</p>
        </#if>
        <div class="document-access-denied-actions mt-4">
            <a class="btn btn-primary" href="/">${backHomeText}</a>
            <a class="btn btn-outline-secondary" href="javascript:history.back()">${goBackText}</a>
        </div>
    </div>
</section>
