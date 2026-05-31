<div class="domain-playground"
     data-initial-corpus-id="${(corpusId)!-1}">
    <div class="domain-shell">
        <aside class="domain-sidebar">
            <div class="domain-sidebar-header">
                <div>
                    <h5 class="mb-0 color-prime">
                        <i class="fas fa-project-diagram mr-2"></i>Domains
                    </h5>
                    <p class="mb-0 small-font text">Graph scope explorer</p>
                </div>
                <button class="btn domain-refresh-btn" data-trigger="hover" data-toggle="popover"
                        data-placement="right" data-content="Reload domain graph">
                    <i class="fas fa-sync-alt"></i>
                </button>
            </div>

            <div class="domain-type-bar"></div>

            <div class="domain-explorer-body">
                <nav class="domain-association-rail" aria-label="Association lens">
                    <button class="domain-lens active" data-association="Membership" data-trigger="hover"
                            data-toggle="popover" data-placement="right"
                            data-content="Membership: whole to part traversal">
                        <i class="fas fa-sitemap"></i>
                        <span>Membership</span>
                    </button>
                </nav>

                <section class="domain-explorer">
                    <div class="domain-explorer-toolbar">
                        <input type="text" class="form-control domain-node-query" placeholder="Filter focused type..."/>
                        <button class="btn domain-node-query-btn">
                            <i class="fas fa-search"></i>
                        </button>
                    </div>
                    <div class="domain-node-list"></div>
                    <div class="domain-node-pager">
                        <button class="btn domain-page-prev"><i class="fas fa-chevron-left"></i></button>
                        <span class="domain-page-state small-font text">0 / 0</span>
                        <button class="btn domain-page-next"><i class="fas fa-chevron-right"></i></button>
                    </div>
                </section>
            </div>
        </aside>

        <main class="domain-main">
            <header class="domain-scope-header">
                <div class="domain-breadcrumbs"></div>
                <div class="domain-workspace-tabs">
                    <button class="btn active" data-mode="Documents"><i class="fas fa-file-alt"></i> Documents</button>
                    <button class="btn" data-mode="Pages"><i class="fas fa-copy"></i> Pages</button>
                    <button class="btn" data-mode="Annotations"><i class="fas fa-tags"></i> Annotations</button>
                    <button class="btn" data-mode="Timeline"><i class="fas fa-stream"></i> Timeline</button>
                    <button class="btn" data-mode="Network"><i class="fas fa-project-diagram"></i> Network</button>
                    <button class="btn" data-mode="Table"><i class="fas fa-table"></i> Table</button>
                    <button class="btn" data-mode="Reader"><i class="fas fa-book-open"></i> Reader</button>
                </div>
            </header>

            <section class="domain-workspace">
                <div class="domain-workspace-summary"></div>
                <div class="domain-workspace-content"></div>
            </section>

            <section class="domain-reference-panel">
                <div class="domain-reference-header">
                    <h6 class="mb-0"><i class="fas fa-link mr-2"></i>Associations</h6>
                    <div>
                        <button class="btn domain-reference-direction active" data-direction="OUTGOING">Outgoing</button>
                        <button class="btn domain-reference-direction" data-direction="INCOMING">Incoming</button>
                    </div>
                </div>
                <div class="domain-reference-content"></div>
            </section>
        </main>
    </div>
</div>
