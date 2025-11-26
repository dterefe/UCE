<section class="feedback-header">
    <h1>${title}</h1>
    <p class="subtitle">${subtitle}</p>
    <div class="badges">
        <#list metaBadges as badge>
            <span class="badge">
                <span class="label">${badge.label}</span>
                <span class="value">${badge.value}</span>
            </span>
        </#list>
    </div>
</section>

<section class="feedback-overview">
    <#list overviewCards as card>
        <article class="metric-card">
            <header>${card.title}</header>
            <div class="value">${card.value?string["#,##0.##"]}</div>
            <ul class="baseline">
                <li>Min: ${card.min}</li>
                <li>Max: ${card.max}</li>
                <li>Mean: ${card.mean?string["#,##0.##"]}</li>
                <li>Std: ${card.stdDev?string["#,##0.##"]}</li>
            </ul>
            <small class="diff">
                Abweichung: ${card.diffPercent?string["+#,##0.##;-#,##0.##"]} %
            </small>
        </article>
    </#list>
</section>

<#if overviewHighlights?has_content>
<section class="feedback-highlights">
    <ul>
        <#list overviewHighlights as item>
            <li>${item}</li>
        </#list>
    </ul>
</section>
</#if>

<#if topUrls?has_content>
<section class="feedback-urls">
    <h2>Top URLs</h2>
    <table>
        <thead><tr><th>#</th><th>URL</th><th>Wert</th></tr></thead>
        <tbody>
        <#list topUrls as url>
            <tr>
                <td>${url.rank}</td>
                <td><a href="${url.url}" target="_blank">${url.url}</a></td>
                <td>${url.hits}</td>
            </tr>
        </#list>
        </tbody>
    </table>
</section>
</#if>

<section class="feedback-content">
    <#list contentCards as card>
        <#if card.type == "section-header">
            <h3>${card.title}</h3>
        <#elseif card.type == "text">
            <article class="text-card">
                <header>
                    <strong>${card.title}</strong>
                    <small>${card.subtitle!""}</small>
                </header>
                <div class="body">${card.body?no_esc}</div>
            </article>
        <#elseif card.type == "chart">
            <article class="chart-card">
                <header>
                    <strong>${card.title}</strong>
                    <small>${card.subtitle!""}</small>
                </header>
                <#if card.chartType == "image">
                    <img src="${card.labels[0]}" alt="${card.title}" class="img-fluid"/>
                <#else>
                    <pre>${card.series?join(", ")}</pre>
                </#if>
            </article>
        <#elseif card.type == "table">
            <article class="table-card">
                <header>
                    <strong>${card.title}</strong>
                    <small>${card.subtitle!""}</small>
                </header>
                <table>
                    <tbody>
                    <#list card.rows as row>
                        <tr>
                            <#list row.cells as cell>
                                <td>${cell}</td>
                            </#list>
                        </tr>
                    </#list>
                    </tbody>
                </table>
            </article>
        </#if>
    </#list>
</section>
