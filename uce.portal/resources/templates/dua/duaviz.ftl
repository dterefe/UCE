<section class="duaviz-shell" data-state="ready">
    <aside class="duaviz-sidebar" aria-label="Corpus type system">
        <div class="duaviz-panel-header">
            <div>
                <p class="duaviz-kicker mb-1">Corpus CAS</p>
                <h5 class="mb-0">Type System</h5>
            </div>
            <button class="duaviz-icon-btn duaviz-refresh-btn" type="button" title="Refresh">
                <i class="fas fa-sync-alt"></i>
            </button>
        </div>
        <div class="duaviz-search">
            <i class="fas fa-search"></i>
            <input class="duaviz-type-filter" type="search" placeholder="Filter types">
        </div>
        <div class="duaviz-accordion-stack">
            <section class="duaviz-accordion duaviz-accordion-artifacts" data-section="artifact">
                <button class="duaviz-accordion-toggle" type="button" data-section="artifact">
                    <span><i class="fas fa-chevron-down"></i> Artifact hierarchy</span>
                    <span class="duaviz-count">0</span>
                </button>
                <div class="duaviz-tree duaviz-artifact-tree"><div class="duaviz-empty-line">Loading type hierarchy…</div></div>
            </section>
            <section class="duaviz-accordion duaviz-accordion-annotations" data-section="annotation">
                <button class="duaviz-accordion-toggle" type="button" data-section="annotation">
                    <span><i class="fas fa-chevron-down"></i> UIMA annotations</span>
                    <span class="duaviz-count">0</span>
                </button>
                <div class="duaviz-tree duaviz-annotation-type-tree"><div class="duaviz-empty-line">Loading feature structures…</div></div>
            </section>
        </div>
    </aside>

    <main class="duaviz-main">
        <section class="duaviz-reader-shell">
            <div class="duaviz-ready-panel">
                <div>
                    <p class="duaviz-kicker mb-1">Ready</p>
                    <h4 class="duaviz-selection-title mb-0">Select a type or document</h4>
                </div>
                <div class="duaviz-mode-toggle" aria-label="Middle pane mode">
                    <button class="duaviz-mode-btn active" type="button" data-mode="reader">Reader</button>
                </div>
            </div>
            <div class="duaviz-view-chipbar" aria-label="Document views"></div>
            <section class="duaviz-document-gallery-container"></section>
            <div class="duaviz-reader-layout">
                <article class="duaviz-document-reader">
                    <div class="duaviz-document-meta"></div>
                    <div class="duaviz-document-text"><div class="duaviz-reader-placeholder">Expand a type to load instances lazily.</div></div>
                </article>
            </div>
        </section>
    </main>

    <aside class="duaviz-feature-panel" aria-label="Feature structure details">
        <div class="duaviz-panel-header compact">
            <div>
                <p class="duaviz-kicker mb-1">CAS Feature Structure</p>
                <h6 class="mb-0">Features</h6>
            </div>
        </div>
        <div class="duaviz-feature-summary"></div>
        <div class="duaviz-feature-table"></div>
        <section class="duaviz-viztools" aria-label="Visualization tools">
            <div class="duaviz-panel-header compact">
                <div>
                    <p class="duaviz-kicker mb-1">VizTools</p>
                </div>
            </div>
            <div class="duaviz-viz-gallery"></div>
            <div class="duaviz-viz-component-detail"></div>
        </section>
    </aside>
</section>
