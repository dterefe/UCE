<head>
    <link rel="stylesheet" href="css/bootstrap/bootstrap.min.css">
    <link rel="stylesheet" href="css/animate.min.css">
    <style>
        <#include "css/site.css">
    </style>
</head>

<div class="w-100 h-100 text-center p-3 bg-lightgray ">
    <p class="font-weight-bold mb-2 text-danger text-center w-100">${languageResource.get("unexpectedError")}</p>
    <img src="img/logo.png" style="width: 60px"/>
    <p class="text-danger text-center mt-2">
        <#if information??>
            ${information}
        </#if>
    </p>
</div>
