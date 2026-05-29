var domainPlayground = (function () {
    const state = {
        corpusId: -1,
        types: [],
        focusedType: null,
        activeLens: 'Membership',
        selectedNode: null,
        breadcrumbs: [],
        mode: 'Documents',
        nodeSkip: 0,
        nodeTake: 50,
        referenceDirection: 'OUTGOING'
    };

    const associationIcons = {
        Membership: 'fa-sitemap',
        Sequence: 'fa-sort-amount-down',
        Reference: 'fa-link',
        Equivalence: 'fa-equals'
    };

    function init() {
        const $root = root();
        if (!$root.length || $root.data('initialized')) return;
        $root.data('initialized', true);
        installHandlers();
        syncCorpus();
        loadTypes();
    }

    function root() {
        return $('.domain-playground').first();
    }

    function syncCorpus() {
        const selected = $('#corpus-select option:selected').attr('data-id');
        const fallback = root().attr('data-initial-corpus-id');
        state.corpusId = parseInt(selected || fallback || '-1');
    }

    function installHandlers() {
        $('body').on('click', '.domain-refresh-btn', function () {
            syncCorpus();
            resetForCorpus();
            loadTypes();
        });

        $('body').on('click', '.domain-type-chip', function () {
            state.focusedType = $(this).attr('data-uima-type');
            state.nodeSkip = 0;
            state.selectedNode = null;
            renderTypeBar();
            loadNodes();
            renderWorkspace();
        });

        $('body').on('click', '.domain-lens', function () {
            state.activeLens = $(this).attr('data-association');
            $('.domain-lens').removeClass('active');
            $(this).addClass('active');
            if (state.selectedNode) loadAssociations(state.selectedNode, $(this).closest('.domain-node-row'));
            renderWorkspace();
        });

        $('body').on('click', '.domain-node-row', function (event) {
            if ($(event.target).closest('.domain-expand-btn').length) return;
            const node = $(this).data('node');
            if (!node) return;
            state.selectedNode = node;
            pushBreadcrumb(node);
            $('.domain-node-row').removeClass('active');
            $(this).addClass('active');
            loadAssociations(node, $(this));
            renderWorkspace();
            loadReferences();
        });

        $('body').on('click', '.domain-expand-btn', function (event) {
            event.stopPropagation();
            const $row = $(this).closest('.domain-node-row');
            const node = $row.data('node');
            if (!node) return;
            state.selectedNode = node;
            loadAssociations(node, $row);
        });

        $('body').on('click', '.domain-child-type-jump', function () {
            const type = $(this).attr('data-uima-type');
            const nodeJson = $(this).closest('.domain-node-row').attr('data-node-json');
            if (nodeJson) {
                try { pushBreadcrumb(JSON.parse(decodeURIComponent(nodeJson))); } catch (e) {}
            }
            state.focusedType = type;
            state.nodeSkip = 0;
            renderTypeBar();
            loadNodes();
        });

        $('body').on('click', '.remove-crumb', function () {
            const idx = parseInt($(this).closest('.domain-crumb').attr('data-index'));
            state.breadcrumbs = state.breadcrumbs.slice(0, idx);
            state.selectedNode = state.breadcrumbs.length ? state.breadcrumbs[state.breadcrumbs.length - 1] : null;
            renderBreadcrumbs();
            renderWorkspace();
        });

        $('body').on('click', '.domain-workspace-tabs .btn', function () {
            state.mode = $(this).attr('data-mode');
            $('.domain-workspace-tabs .btn').removeClass('active');
            $(this).addClass('active');
            renderWorkspace();
        });

        $('body').on('click', '.domain-reference-direction', function () {
            state.referenceDirection = $(this).attr('data-direction');
            $('.domain-reference-direction').removeClass('active');
            $(this).addClass('active');
            loadReferences();
        });

        $('body').on('click', '.domain-page-prev', function () {
            state.nodeSkip = Math.max(0, state.nodeSkip - state.nodeTake);
            loadNodes();
        });

        $('body').on('click', '.domain-page-next', function () {
            state.nodeSkip += state.nodeTake;
            loadNodes();
        });

        $('body').on('click', '.domain-node-query-btn', function () {
            state.nodeSkip = 0;
            loadNodes();
        });

        $('body').on('keydown', '.domain-node-query', function (event) {
            if (event.key === 'Enter') {
                state.nodeSkip = 0;
                loadNodes();
            }
        });

        $('body').on('change', '#corpus-select', function () {
            if (currentView === 'domain') {
                syncCorpus();
                resetForCorpus();
                loadTypes();
            }
        });
    }

    function resetForCorpus() {
        state.types = [];
        state.focusedType = null;
        state.selectedNode = null;
        state.breadcrumbs = [];
        state.nodeSkip = 0;
    }

    async function loadTypes() {
        const $typeBar = $('.domain-type-bar');
        $typeBar.html(loader('Loading domain types...'));
        try {
            const response = await $.getJSON('/api/domain/types?corpusId=' + encodeURIComponent(state.corpusId));
            state.types = response.types || [];
            if (!state.focusedType && state.types.length) {
                state.focusedType = state.types[0].uimaType;
            }
            renderTypeBar();
            renderLensAvailability();
            loadNodes();
            renderBreadcrumbs();
            renderWorkspace();
        } catch (e) {
            $typeBar.html(errorBox('Could not load domain types.'));
        }
    }

    async function loadNodes() {
        const $list = $('.domain-node-list');
        if (!state.focusedType) {
            $list.html(emptyBox('No domain types available for this corpus.'));
            return;
        }
        $list.html(loader('Loading ' + typeLabel(state.focusedType) + '...'));
        try {
            const response = await postJson('/api/domain/nodes', {
                corpusId: state.corpusId,
                uimaType: state.focusedType,
                query: $('.domain-node-query').val() || '',
                skip: state.nodeSkip,
                take: state.nodeTake
            });
            renderNodePage(response.page);
        } catch (e) {
            $list.html(errorBox('Could not load domain nodes.'));
        }
    }

    async function loadAssociations(node, $row) {
        const $children = ensureChildrenContainer($row);
        $children.html(loader('Loading ' + state.activeLens + '...'));
        try {
            const response = await postJson('/api/domain/associations', {
                corpusId: state.corpusId,
                sourceUids: [node.uid],
                associationType: state.activeLens,
                direction: state.activeLens === 'Equivalence' ? 'BOTH' : 'OUTGOING',
                skip: 0,
                take: 80
            });
            renderAssociationGroups($children, response.page);
        } catch (e) {
            $children.html(errorBox('Could not load associated domains.'));
        }
    }

    async function loadReferences() {
        const $panel = $('.domain-reference-content');
        if (!state.selectedNode) {
            $panel.html(emptyBox('Select a domain node to inspect references.'));
            return;
        }
        $panel.html(loader('Loading references...'));
        try {
            const response = await postJson('/api/domain/associations', {
                corpusId: state.corpusId,
                sourceUids: [state.selectedNode.uid],
                associationType: 'Reference',
                direction: state.referenceDirection,
                skip: 0,
                take: 30
            });
            renderReferencePanel(response.page);
        } catch (e) {
            $panel.html(errorBox('Could not load references.'));
        }
    }

    async function renderWorkspace() {
        renderBreadcrumbs();
        const selectedUids = state.breadcrumbs.map((node) => node.uid);
        try {
            const response = await postJson('/api/domain/scope/preview', {
                corpusId: state.corpusId,
                selectedUids: selectedUids
            });
            renderSummary(response.preview);
            renderWorkspaceContent(response.preview);
        } catch (e) {
            $('.domain-workspace-summary').html('');
            $('.domain-workspace-content').html(errorBox('Could not preview current domain scope.'));
        }
    }

    function renderTypeBar() {
        if (!state.types.length) {
            $('.domain-type-bar').html(emptyBox('No domain graph data found.'));
            return;
        }
        $('.domain-type-bar').html(state.types.map((type) => {
            const active = type.uimaType === state.focusedType ? ' active' : '';
            return '<button class="domain-type-chip' + active + '" data-uima-type="' + esc(type.uimaType) + '">' +
                '<i class="' + iconForType(type.label) + '"></i>' +
                '<span>' + esc(type.label) + '</span>' +
                '<span class="count">' + type.count + '</span>' +
                '</button>';
        }).join(''));
    }

    function renderLensAvailability() {
        const focused = state.types.find((type) => type.uimaType === state.focusedType);
        $('.domain-lens').each(function () {
            const association = $(this).attr('data-association');
            const count = focused && focused.associationCounts ? (focused.associationCounts[association] || 0) : 0;
            $(this).toggleClass('unavailable', count === 0);
        });
    }

    function renderNodePage(page) {
        const items = page && page.items ? page.items : [];
        if (!items.length) {
            $('.domain-node-list').html(emptyBox('No nodes match this focused type.'));
        } else {
            $('.domain-node-list').html(items.map(nodeRow).join(''));
            $('.domain-node-row').each(function () {
                const node = JSON.parse(decodeURIComponent($(this).attr('data-node-json')));
                $(this).data('node', node);
            });
        }
        const start = page ? page.skip + 1 : 0;
        const end = page ? Math.min(page.total, page.skip + page.take) : 0;
        $('.domain-page-state').text((page && page.total ? (start + '-' + end + ' / ' + page.total) : '0 / 0'));
        $('.domain-page-prev').prop('disabled', !page || page.skip <= 0);
        $('.domain-page-next').prop('disabled', !page || page.skip + page.take >= page.total);
    }

    function nodeRow(node) {
        const encoded = encodeURIComponent(JSON.stringify(node));
        return '<div class="domain-node-row" data-node-json="' + encoded + '">' +
            '<button class="btn domain-expand-btn"><i class="fas fa-chevron-right"></i></button>' +
            '<div class="min-width-0">' +
            '<div class="node-label">' + esc(node.name || node.uid) + '</div>' +
            '<div class="node-type">' + esc(node.label || typeLabel(node.uimaType)) + '</div>' +
            '</div>' +
            '<div class="node-counts">' + node.outgoingCount + ' / ' + node.incomingCount + '</div>' +
            '</div>';
    }

    function renderAssociationGroups($target, page) {
        const items = page && page.items ? page.items : [];
        if (!items.length) {
            $target.html(emptyBox('No ' + state.activeLens + ' associations.'));
            return;
        }
        const groups = {};
        items.forEach((item) => {
            const key = item.target.label || typeLabel(item.target.uimaType);
            if (!groups[key]) groups[key] = [];
            groups[key].push(item);
        });
        let html = '';
        Object.keys(groups).sort().forEach((label) => {
            html += '<div class="domain-child-group">';
            html += '<div class="domain-child-group-title">' + esc(label) + ' <span class="text">(' + groups[label].length + ')</span></div>';
            groups[label].forEach((item) => {
                html += nodeRow(item.target).replace('domain-node-row"', 'domain-node-row domain-child-type-jump" data-uima-type="' + esc(item.target.uimaType) + '"');
            });
            html += '</div>';
        });
        $target.html(html);
    }

    function renderReferencePanel(page) {
        const items = page && page.items ? page.items : [];
        if (!items.length) {
            $('.domain-reference-content').html(emptyBox('No references for the selected direction.'));
            return;
        }
        $('.domain-reference-content').html('<div class="domain-workspace-grid">' + items.map((item) =>
            '<div class="domain-workspace-card">' +
            '<h6 class="mb-1">' + esc(item.target.name || item.target.uid) + '</h6>' +
            '<p class="mb-1 small-font color-prime">' + esc(item.target.label) + '</p>' +
            '<p class="mb-0 xsmall-font text">' + esc(item.edge.name || item.edge.label || 'Reference') + '</p>' +
            '</div>'
        ).join('') + '</div>');
    }

    function renderBreadcrumbs() {
        if (!state.breadcrumbs.length) {
            $('.domain-breadcrumbs').html('<span class="domain-crumb"><i class="fas fa-globe"></i><span class="crumb-name">Corpus scope</span></span>');
            return;
        }
        $('.domain-breadcrumbs').html(state.breadcrumbs.map((node, index) =>
            '<span class="domain-crumb" data-index="' + index + '">' +
            '<i class="' + iconForType(node.label) + '"></i>' +
            '<span class="crumb-name">' + esc(node.label) + ': ' + esc(node.name || node.uid) + '</span>' +
            '<i class="fas fa-times remove-crumb"></i>' +
            '</span>'
        ).join(''));
    }

    function renderSummary(preview) {
        preview = preview || {};
        const typeCount = preview.typeCounts ? Object.keys(preview.typeCounts).length : 0;
        $('.domain-workspace-summary').html(
            summaryTile('Nodes', preview.nodeCount || 0) +
            summaryTile('Documents', preview.documentCount || 0) +
            summaryTile('Pages', preview.pageCount || 0) +
            summaryTile('Types', typeCount)
        );
    }

    function renderWorkspaceContent(preview) {
        const content = $('.domain-workspace-content');
        const selected = state.selectedNode;
        if (state.mode === 'Table') {
            const rows = state.breadcrumbs.length ? state.breadcrumbs : (selected ? [selected] : []);
            content.html(renderTable(rows));
        } else if (state.mode === 'Network') {
            renderNetworkMode(content);
        } else if (state.mode === 'Timeline') {
            content.html(renderTimeline(preview));
        } else if (state.mode === 'Reader') {
            content.html(selected && selected.documentId > 0
                ? '<div class="domain-workspace-card"><h6>Reader target</h6><p class="text mb-2">Open the document connected to this domain node.</p><button class="btn btn-primary open-document" data-id="' + selected.documentId + '"><i class="fas fa-book-open mr-1"></i>Open Reader</button></div>'
                : emptyBox('Select a document- or page-backed domain node to open the reader.'));
        } else {
            content.html(renderDefaultCards(preview));
        }
    }

    async function renderNetworkMode(content) {
        if (!state.selectedNode) {
            content.html(emptyBox('Select a domain node to show its local graph.'));
            return;
        }
        content.html(loader('Loading local graph...'));
        try {
            const response = await postJson('/api/domain/ego', {
                corpusId: state.corpusId,
                selectedUids: [state.selectedNode.uid],
                associationTypes: ['Membership', 'Sequence', 'Reference', 'Equivalence'],
                depth: 1
            });
            const graph = response.graph || {nodes: [], edges: []};
            content.html('<div class="domain-workspace-card"><h6>Local domain graph</h6><p class="mb-2 text">' +
                graph.nodes.length + ' nodes, ' + graph.edges.length + ' associations</p>' +
                '<div class="domain-workspace-grid">' + graph.nodes.map((node) =>
                    '<div class="domain-summary-tile"><label>' + esc(node.label) + '</label><strong class="small-font">' + esc(node.name || node.uid) + '</strong></div>'
                ).join('') + '</div></div>');
        } catch (e) {
            content.html(errorBox('Could not render local graph.'));
        }
    }

    function renderDefaultCards(preview) {
        const selected = state.selectedNode;
        const title = selected ? (selected.name || selected.uid) : 'Domain scope';
        const subtitle = selected ? selected.label : 'No focused node selected';
        return '<div class="domain-workspace-card">' +
            '<h5 class="mb-1 color-prime">' + esc(title) + '</h5>' +
            '<p class="mb-3 text">' + esc(state.mode) + ' mode · ' + esc(subtitle) + '</p>' +
            '<div class="domain-workspace-grid">' +
            Object.keys((preview && preview.typeCounts) || {}).map((type) =>
                '<div class="domain-summary-tile"><label>' + esc(typeLabel(type)) + '</label><strong>' + preview.typeCounts[type] + '</strong></div>'
            ).join('') +
            '</div></div>';
    }

    function renderTimeline(preview) {
        const counts = (preview && preview.typeCounts) || {};
        return '<div class="domain-workspace-card"><h6>Scope distribution</h6>' +
            '<div class="domain-workspace-grid">' + Object.keys(counts).map((type) =>
                '<div class="domain-summary-tile"><label>' + esc(typeLabel(type)) + '</label><strong>' + counts[type] + '</strong></div>'
            ).join('') + '</div></div>';
    }

    function renderTable(rows) {
        if (!rows.length) return emptyBox('Select domain nodes to inspect them in table mode.');
        return '<table class="domain-table"><thead><tr><th>Type</th><th>Name</th><th>UID</th><th>Document</th><th>Features</th></tr></thead><tbody>' +
            rows.map((node) => '<tr><td>' + esc(node.label) + '</td><td>' + esc(node.name || '') + '</td><td>' + esc(node.uid) + '</td><td>' + (node.documentId || '-') + '</td><td><code>' + esc(node.features || '') + '</code></td></tr>').join('') +
            '</tbody></table>';
    }

    function pushBreadcrumb(node) {
        const idx = state.breadcrumbs.findIndex((crumb) => crumb.uid === node.uid);
        if (idx >= 0) {
            state.breadcrumbs = state.breadcrumbs.slice(0, idx + 1);
        } else {
            state.breadcrumbs.push(node);
        }
    }

    function ensureChildrenContainer($row) {
        let $children = $row.next('.domain-node-children');
        if (!$children.length) {
            $children = $('<div class="domain-node-children"></div>');
            $row.after($children);
        }
        return $children;
    }

    function summaryTile(label, value) {
        return '<div class="domain-summary-tile"><label>' + esc(label) + '</label><strong>' + esc(value) + '</strong></div>';
    }

    function postJson(url, payload) {
        return $.ajax({
            url: url,
            type: 'POST',
            data: JSON.stringify(payload || {}),
            contentType: 'application/json',
            dataType: 'json'
        });
    }

    function iconForType(label) {
        label = String(label || '').toLowerCase();
        if (label.includes('collection')) return 'fas fa-archive';
        if (label.includes('journal')) return 'fas fa-newspaper';
        if (label.includes('volume')) return 'fas fa-layer-group';
        if (label.includes('issue')) return 'fas fa-book';
        if (label.includes('article')) return 'fas fa-file-alt';
        if (label.includes('page')) return 'fas fa-copy';
        if (label.includes('corpus')) return 'fas fa-database';
        if (label.includes('document')) return 'fas fa-file';
        return 'fas fa-circle';
    }

    function typeLabel(type) {
        if (!type) return '-';
        const parts = String(type).split('.');
        return parts[parts.length - 1];
    }

    function loader(text) {
        return '<div class="domain-empty"><i class="fas fa-spinner rotate mr-1"></i>' + esc(text) + '</div>';
    }

    function emptyBox(text) {
        return '<div class="domain-empty">' + esc(text) + '</div>';
    }

    function errorBox(text) {
        return '<div class="domain-empty text-danger">' + esc(text) + '</div>';
    }

    function esc(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    return {
        init: init,
        reload: function () {
            syncCorpus();
            resetForCorpus();
            loadTypes();
        }
    };
}());

$(document).ready(function () {
    domainPlayground.init();
});
