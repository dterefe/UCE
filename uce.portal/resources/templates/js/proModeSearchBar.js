(function($) {
    'use strict';

    var PROMODE_COMMANDS = {
        taxon: ['K::', 'P::', 'C::', 'O::', 'F::', 'G::', 'S::'],
        location: ['LOC::', 'R::'],
        time: ['Y::', 'M::', 'D::', 'E::', 'T::']
    };

    var TAXON_RANKS = { 'K::': 'Kingdom', 'P::': 'Phylum', 'C::': 'Class', 'O::': 'Order', 'F::': 'Family', 'G::': 'Genus', 'S::': 'Species' };
    var TIME_CODES = { 'Y::': 'Year', 'M::': 'Month', 'D::': 'Day', 'E::': 'Season', 'T::': 'Year Range' };
    var LOC_CODES = { 'LOC::': 'Feature Class', 'R::': 'Radius' };

    var MONTHS = ['January','February','March','April','May','June','July','August','September','October','November','December'];
    var SEASONS = ['Spring','Summer','Fall','Winter'];
    var SLASH_MODIFIERS = [
        { code: '/l', label: 'Lemma', group: 'Token' },
        { code: '/c', label: 'Case', group: 'Token' },
        { code: '/r', label: 'Regex', group: 'Token' },
        { code: '/s', label: 'Sentence', group: 'Span' },
        { code: '/p', label: 'Paragraph', group: 'Span' },
        { code: '/n', label: 'Near', group: 'Span' },
        { code: '/w', label: 'Within', group: 'Span' },
        { code: '/t', label: 'Type', group: 'Annotation' },
        { code: '/f', label: 'Feature', group: 'Annotation' },
        { code: '/d', label: 'Dependency', group: 'Annotation' },
        { code: '/o', label: 'Option', group: 'Annotation' }
    ];
    var TOKEN_RE = /'[^'\\]*(?:\\.[^'\\]*)*'(?:\/[a-z])?|"[^"\\]*(?:\\.[^"\\]*)*"(?:\/[a-z])?|<->|<\d+>|<=|>=|!=|[<>=]|[!|&()]|[^\s!|&()<>=]+/g;

    var ALL_COMMANDS = [].concat(PROMODE_COMMANDS.taxon, PROMODE_COMMANDS.location, PROMODE_COMMANDS.time);
    var CMD_CATEGORY = {};
    ALL_COMMANDS.forEach(function(c) {
        if (PROMODE_COMMANDS.taxon.indexOf(c) >= 0) CMD_CATEGORY[c] = 'taxon';
        else if (PROMODE_COMMANDS.location.indexOf(c) >= 0) CMD_CATEGORY[c] = 'location';
        else CMD_CATEGORY[c] = 'time';
    });

    window.ProModeSearchBar = function(el) {
        this.$root = $(el);
        this.chips = [];           // content tokens only: { type:'text'|'command', ... }
        this.operators = [];       // operators[i] between chips[i] and chips[i+1]: 'AND'|'OR'|'NOT'|'<->'|'<N>'|comparisons
        this._leadingNot = false;  // NOT before first chip
        this._selStart = -1;
        this._selEnd = -1;
        this._dragMode = null;
        this._commandMode = null;
        this._toolbarTimeout = null;
        this._editingIndex = -1;
        this._editingChip = null;
        this._suppressEditBlur = false;
        this._insertionIndex = null;
        this._autoquote = true;
        this._operatorSelectionIndex = -1;
        this.labels = {};
        this._init();
    };

    var P = window.ProModeSearchBar.prototype;

    P._init = function() {
        var self = this;
        this.$chipRow = this.$root.find('.promode-chip-row');
        this.$textInput = this.$root.find('.promode-text-input');
        this.$toolbar = this.$root.find('.promode-toolbar');
        this.$shortcutBar = this.$root.find('.promode-shortcut-bar');
        this.$enumRow = this.$root.find('.promode-enum-dropdown');
        this.$error = this.$root.find('.promode-syntax-error');
        this.$groupingOverlay = this.$root.find('.promode-grouping-overlay');
        this.$joinOverlay = this.$root.find('.promode-join-overlay');
        this.$cmdIndicator = this.$root.find('.promode-command-indicator');
        this.labels = {
            and: this._readLabel('and', 'AND'),
            or: this._readLabel('or', 'OR'),
            not: this._readLabel('not', 'NOT'),
            followedBy: this._readLabel('followed-by', 'FOLLOWED BY'),
            distance: this._readLabel('distance', 'DISTANCE')
        };
        this._detachHelperRows();

        this.$textInput.on('keydown', function(e) { self._onKeyDown(e); });
        this.$textInput.on('keyup', function(e) { self._onKeyUp(e); });
        this.$textInput.on('input', function() { self._onInput(); });
        this.$textInput.on('focus', function() { self._scheduleToolbarUpdate(); });
        this.$textInput.on('blur', function() {
            if (!self._suppressEditBlur) self._finishEditingChip(true);
        });
        this.$textInput.on('paste', function(e) { self._onPaste(e); });
        this.$root.on('click', function(e) { self._onContainerClick(e); });
        this.$root.on('mouseenter mousemove', function() { self._ensureToolbarOpen(); });
        this.$root.on('focusin', function() { self._ensureToolbarOpen(); });
        this.$chipRow.on('mousedown', '.promode-chip', function(e) { self._onChipMouseDown(e, this); });
        this.$chipRow.on('mousemove', function(e) { self._onChipRowMouseMove(e); });
        this.$chipRow.on('contextmenu', function(e) {
            if (self._dragMode) {
                e.preventDefault();
                self._cancelDragSelect();
            }
        });
        $(document).on('mouseup.promode', function(e) { self._onGlobalMouseUp(e); });
        $(document).on('mousemove.promode', function(e) { self._onGlobalMouseMove(e); });
        $(document).on('keydown.promode', function(e) {
            if (document.activeElement === self.$textInput[0]) {
                self._onFocusedInputKeyDown(e);
            }
        });
        $(window).on('resize.promode', function() { self._positionDetachedHelperRows(); });

        // Delegate delete button clicks on chip row (handles dynamically created chips)
        this.$chipRow.on('mousedown', '.promode-chip-delete', function(e) {
            e.preventDefault();
            e.stopPropagation();
            var idx = $(this).closest('.promode-chip').data('chip-index');
            if (idx !== undefined) {
                self._removeChip(idx);
            }
        });
    };

    P.destroy = function() {
        $(document).off('.promode');
        $(window).off('.promode');
        this.$root.off();
        this.$chipRow.off();
        if (this._toolbarTimeout) clearTimeout(this._toolbarTimeout);
    };

    P._detachHelperRows = function() {
        var self = this;
        var $header = this.$root.closest('.search-header');
        if (!$header.length) return;

        this.$detachedHost = $header.next('.promode-detached-helper-host');
        if (!this.$detachedHost.length) {
            this.$detachedHost = $('<div class="promode-detached-helper-host"></div>');
            this.$detachedHost.insertAfter($header);
        }

        this.$detachedHost.append(this.$shortcutBar, this.$toolbar, this.$enumRow, this.$error);
        this.$detachedHost.on('mouseenter mousemove focusin', function() {
            self._ensureToolbarOpen();
        });
        this._positionDetachedHelperRows();
        this._syncDetachedHostVisibility();
    };

    P._positionDetachedHelperRows = function() {
        if (!this.$detachedHost || !this.$detachedHost.length) return;

        var bar = this.$root.find('.promode-search-bar')[0];
        if (!bar) return;

        var rect = bar.getBoundingClientRect();
        this.$detachedHost.css({
            '--promode-helper-left': rect.left + 'px',
            '--promode-helper-max-width': Math.min(860, rect.width) + 'px'
        });
    };

    P._syncDetachedHostVisibility = function() {
        if (!this.$detachedHost || !this.$detachedHost.length) return;
        var visible = this.$root.is(':visible') ||
            this.$shortcutBar.hasClass('promode-shortcut-visible') ||
            this.$toolbar.hasClass('promode-toolbar-visible') ||
            this.$enumRow.hasClass('promode-enum-visible') ||
            this.$error.hasClass('promode-error-visible');
        this.$detachedHost.toggleClass('promode-detached-helper-visible', visible);
        if (visible) this._positionDetachedHelperRows();
    };

    P.showPersistentToolbar = function() {
        if (!this.$toolbar.children().length) {
            this._buildFullToolbar();
        }
        this.$toolbar.addClass('promode-toolbar-visible');
        this._renderShortcutBar();
        this._syncDetachedHostVisibility();
    };

    P._restoreFullToolbar = function() {
        this.$toolbar.removeClass('promode-toolbar-operator-select').empty();
        this._buildFullToolbar();
        this.$toolbar.addClass('promode-toolbar-visible');
        this._renderShortcutBar('text');
        this._syncDetachedHostVisibility();
    };

    P._renderShortcutBar = function(context) {
        if (!this.$shortcutBar || !this.$shortcutBar.length) return;
        var mode = context || (this._commandMode ? 'command' : 'text');
        var items;

        if (mode === 'command') {
            items = [
                { key: 'ENTER', info: 'apply' },
                { key: 'ESC', info: 'cancel' },
                { key: 'UP/DOWN', info: 'select' }
            ];
        } else if (mode === 'operator') {
            items = [
                { key: 'CLICK', info: 'choose operator' },
                { key: 'ESC', info: 'cancel swap' }
            ];
        } else if (mode === 'chip') {
            items = [
                { key: 'CLICK', info: 'edit' },
                { key: 'SHIFT DRAG', info: 'group' },
                { key: 'CTRL DRAG', info: 'quote/join' },
                { key: 'RIGHT CLICK', info: 'cancel drag' }
            ];
        } else {
            items = [
                { key: 'ALT', info: 'Autoquote ' + (this._autoquote ? 'ON' : 'OFF'), state: this._autoquote ? 'active' : 'off' },
                { key: 'SHIFT SPACE', info: 'literal space' },
                { key: 'ENTER', info: 'commit' },
                { key: 'LEFT/RIGHT', info: 'edit chips' }
            ];
        }

        this.$shortcutBar.empty();
        for (var i = 0; i < items.length; i++) {
            var item = items[i];
            var $shortcut = $('<span class="promode-shortcut"></span>');
            if (item.state) $shortcut.addClass('promode-shortcut-' + item.state);
            $shortcut.append($('<span class="promode-shortcut-key"></span>').text(item.key));
            $shortcut.append($('<span class="promode-shortcut-info"></span>').text(item.info));
            this.$shortcutBar.append($shortcut);
        }
        this.$shortcutBar.addClass('promode-shortcut-visible');
        this._syncDetachedHostVisibility();
    };

    P.hideDetachedHelpers = function() {
        if (this._toolbarTimeout) clearTimeout(this._toolbarTimeout);
        this._hideToolbar(true);
        this.$toolbar.empty();
        this.$shortcutBar.removeClass('promode-shortcut-visible').empty();
        this.$enumRow.removeClass('promode-enum-visible').empty();
        this.$error.removeClass('promode-error-visible').text('');
        this._syncDetachedHostVisibility();
    };

    P.focus = function() {
        this.$textInput.focus();
    };

    P._focusEditorSoon = function() {
        var self = this;
        this.$textInput.focus();
        setTimeout(function() { self.$textInput.focus(); }, 0);
    };

    P._readLabel = function(name, fallback) {
        var raw = String(this.$root.attr('data-label-' + name) || '').trim();
        return raw && raw.indexOf('promodeOperator') !== 0 ? raw : fallback;
    };

    /* ========== VALUE EXPORT ========== */
    P.getValue = function() {
        var parts = [];
        var self = this;

        if (this._leadingNot) {
            parts.push('!');
        }

        for (var i = 0; i < this.chips.length; i++) {
            var c = this.chips[i];

            // operator before this chip (i > 0 means operators[i-1] is between chips[i-1] and chips[i])
            if (i > 0 && this.operators[i - 1]) {
                parts.push(self._operatorSymbol(this.operators[i - 1]));
            }

            // chip value
            if (c.type === 'text') {
                var textValue = c.value;
                if (c.slashModifier) textValue += c.slashModifier;
                if (c.isParen) {
                    parts.push(c.value);
                } else if (c.quoted) {
                    parts.push("'" + this._escapeQueryValue(c.value) + "'" + (c.slashModifier || ''));
                } else if (c.prefixSearch) {
                    parts.push(c.value + ':*' + (c.slashModifier || ''));
                } else {
                    parts.push(textValue);
                }
            } else if (c.type === 'command') {
                parts.push(c.key + this._formatCommandValue(c.value));
            }
        }

        return parts.join(' ');
    };

    P._escapeQueryValue = function(value) {
        return String(value || '').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
    };

    P._formatCommandValue = function(value) {
        var raw = String(value || '').trim();
        if (!raw) return '';
        if (/\s/.test(raw) && !/^(lng|lat|r)=/i.test(raw)) {
            return "'" + this._escapeQueryValue(raw) + "'";
        }
        return raw;
    };

    P._operatorSymbol = function(op) {
        if (op === 'AND') return '&';
        if (op === 'OR') return '|';
        if (op === 'NOT') return '!';
        if (op === 'AND_NOT') return '& !';
        if (op === 'OR_NOT') return '| !';
        return op; // <->, <N>, <, or >
    };

    /* ========== VALUE IMPORT ========== */
    P.setValue = function(raw) {
        this.chips = [];
        this.operators = [];
        this._leadingNot = false;
        this.$chipRow.find('.promode-chip, .promode-operator-chip, .promode-followedby-chip, .promode-paren-text').remove();
        if (raw && raw.trim()) {
            this._parseAndLoad(raw);
        }
        this._rerender();
    };

    P.clear = function() {
        this.chips = [];
        this.operators = [];
        this._leadingNot = false;
        this.$chipRow.find('.promode-chip, .promode-operator-chip, .promode-followedby-chip, .promode-paren-text').remove();
        this.$textInput.val('');
        this.hideDetachedHelpers();
        this.$cmdIndicator.hide();
        this._commandMode = null;
    };

    P._escapeHtml = function(s) {
        return String(s == null ? '' : s)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    };

    /* ========== PARSING (setValue) ========== */
    P._parseAndLoad = function(raw) {
        this._consumeQueryTokens(this._tokenizeQuery(raw), false);
    };

    P._tokenizeQuery = function(raw) {
        var tokens = String(raw || '').match(TOKEN_RE) || [];
        var merged = [];
        for (var i = 0; i < tokens.length; i++) {
            var t = tokens[i];
            var cmd = this._commandPrefixFor(t);
            if (!cmd) {
                merged.push(t);
                continue;
            }

            var value = t.substring(cmd.length);
            if ((value === '' || value.charAt(0) === '"' || value.charAt(0) === "'") && i + 1 < tokens.length) {
                value = value || tokens[++i];
            }
            while (i + 1 < tokens.length && this._canExtendCommandValue(cmd, tokens[i + 1])) {
                value += ' ' + tokens[++i];
            }
            merged.push(cmd + value);
        }
        return merged;
    };

    P._commandPrefixFor = function(text) {
        for (var i = 0; i < ALL_COMMANDS.length; i++) {
            if (String(text || '').indexOf(ALL_COMMANDS[i]) === 0) return ALL_COMMANDS[i];
        }
        return null;
    };

    P._canExtendCommandValue = function(cmd, token) {
        if (cmd !== 'S::') return false;
                if (!token || /^(?:[!|&()<>]=?|=|!=)$/.test(token) || /^<[^>]+>$/.test(token)) return false;
        return !this._commandPrefixFor(token);
    };

    P._unquote = function(text) {
        var raw = String(text || '');
        var q = raw.charAt(0);
        if ((q === "'" || q === '"') && raw.charAt(raw.length - 1) === q) {
            return raw.slice(1, -1).replace(/\\(["'\\])/g, '$1');
        }
        return raw;
    };

    P._consumeQueryTokens = function(tokens, append) {
        if (!tokens) return;
        var self = this;
        if (!append) {
            this.chips = [];
            this.operators = [];
            this._leadingNot = false;
        }
        if (!tokens) return;

        for (var ti = 0; ti < tokens.length; ti++) {
            var t = tokens[ti];

            if (t === '&') {
                if (self.chips.length > 0) {
                    self.operators[self.chips.length - 1] = 'AND';
                }
            } else if (t === '|') {
                if (self.chips.length > 0) {
                    self.operators[self.chips.length - 1] = 'OR';
                }
            } else if (t === '!') {
                if (self.chips.length === 0) {
                    self._leadingNot = true;
                } else if (self.operators[self.chips.length - 1] === 'AND') {
                    self.operators[self.chips.length - 1] = 'AND_NOT';
                } else if (self.operators[self.chips.length - 1] === 'OR') {
                    self.operators[self.chips.length - 1] = 'OR_NOT';
                } else {
                    self.operators[self.chips.length - 1] = 'NOT';
                }
            } else if (t === '<->' || /^<\d+>$/.test(t) || this._isComparisonOperator(t)) {
                if (self.chips.length > 0) {
                    self.operators[self.chips.length - 1] = t;
                }
            } else if (t === '(') {
                self.chips.push({ type: 'text', value: '(', isParen: true });
            } else if (t === ')') {
                self.chips.push({ type: 'text', value: ')', isParen: true });
            } else {
                var q = t.charAt(0);
                if (q === "'" || q === '"') {
                    var quotedModifier = self._extractSlashModifier(t);
                    var quotedToken = quotedModifier ? t.slice(0, -quotedModifier.length) : t;
                    self.chips.push({
                        type: 'text',
                        value: self._unquote(quotedToken),
                        quoted: true,
                        slashModifier: quotedModifier || null
                    });
                } else if (t.endsWith(':*')) {
                    self.chips.push({ type: 'text', value: t.slice(0, -2), prefixSearch: true });
                } else {
                    var cmdPrefix = self._commandPrefixFor(t);
                    if (cmdPrefix) {
                        self.chips.push({
                            type: 'command',
                            key: cmdPrefix,
                            value: self._unquote(t.substring(cmdPrefix.length)),
                            category: CMD_CATEGORY[cmdPrefix] || 'taxon'
                        });
                    } else {
                        var mod = self._extractSlashModifier(t);
                        if (mod) {
                            self.chips.push({ type: 'text', value: t.slice(0, -mod.length), slashModifier: mod, quoted: false });
                        } else {
                            self.chips.push({ type: 'text', value: t, quoted: false });
                        }
                    }
                }
            }
        }
    };

    P._extractSlashModifier = function(text) {
        var raw = String(text || '');
        for (var i = 0; i < SLASH_MODIFIERS.length; i++) {
            var code = SLASH_MODIFIERS[i].code;
            if (raw.length > code.length && raw.slice(-code.length) === code) return code;
        }
        return null;
    };

    /* ========== CHIP MANAGEMENT ========== */
    P._addChip = function(data) {
        var idx = this._normalizedInsertionIndex();
        this.chips.splice(idx, 0, data);
        this._repairOperatorsAroundInsertion(idx);
        this._insertionIndex = idx + 1;
        this._rerender();
        this._focusEditorSoon();
        var $chip = this.$chipRow.find('.promode-chip[data-chip-index="' + idx + '"]');
        $chip.addClass('promode-chip-inserting');
        var self = this;
        setTimeout(function() { $chip.removeClass('promode-chip-inserting'); }, 150);
        return $chip;
    };

    P._normalizedInsertionIndex = function() {
        if (this._insertionIndex === null || this._insertionIndex === undefined) return this.chips.length;
        return Math.max(0, Math.min(this._insertionIndex, this.chips.length));
    };

    P._repairOperatorsAroundInsertion = function(idx) {
        if (this.chips.length < 2) return;

        if (idx > 0 && !this.operators[idx - 1]) {
            this.operators[idx - 1] = 'AND';
        }

        if (idx < this.chips.length - 1 && !this.operators[idx]) {
            this.operators.splice(idx, 0, 'AND');
        }
    };

    P._removeChip = function(idx) {
        if (idx < 0 || idx >= this.chips.length) return;

        // Remove the chip
        this.chips.splice(idx, 1);

        // Clean up adjacent operators
        // operators[idx-1] was between chips[idx-1] and old chips[idx] — remove it
        if (idx > 0 && this.operators[idx - 1] !== undefined) {
            this.operators.splice(idx - 1, 1);
            // After splice, operators indices shift: what was operators[idx] is now operators[idx-1]
            // If there was also an operator at old operators[idx] (now operators[idx-1]), remove it too
            if (this.operators[idx - 1] !== undefined) {
                this.operators.splice(idx - 1, 1);
            }
        } else if (idx === 0 && this.operators[0] !== undefined) {
            // operators[0] was between old chips[0] and old chips[1], now chips[0] is the former chips[1]
            // This operator is now orphaned — remove it
            this.operators.splice(0, 1);
        }

        // If no chips left, clear leading not
        if (this.chips.length === 0) {
            this._leadingNot = false;
        }

        this._rerender();
    };

    P._rerender = function() {
        var $editor = this.$chipRow.find('.promode-inline-editor');
        $editor.detach();
        this.$chipRow.find('.promode-chip, .promode-operator-chip, .promode-followedby-chip, .promode-paren-text').remove();

        // Leading NOT
        if (this._leadingNot) {
            var $notSpan = $('<span class="promode-operator-chip"></span>').text(this.labels.not);
            this.$chipRow.append($notSpan);
        }

        for (var i = 0; i < this.chips.length; i++) {
            if (this._editingIndex < 0 && this._normalizedInsertionIndex() === i) {
                this.$chipRow.append($editor);
            }

            if (i === this._editingIndex) {
                this.$chipRow.append($editor);
            } else {
                var $chip = this._createChipElement(this.chips[i], i);
                this.$chipRow.append($chip);
            }

            // Insert operator text between this chip and the next
            if (this.operators[i] !== undefined) {
                var opText = this.operators[i];
                var $opSpan;
                if (opText === 'AND' || opText === 'OR' || opText === 'NOT' || opText === 'AND_NOT' || opText === 'OR_NOT' || this._isComparisonOperator(opText)) {
                    $opSpan = this._createOperatorElement(opText, i);
                } else {
                    // <-> or <N>
                    $opSpan = this._createDistanceOperatorElement(opText, i);
                }
                this.$chipRow.append($opSpan);
            }
        }

        if (!$.contains(this.$chipRow[0], $editor[0])) {
            this.$chipRow.append($editor);
        }
    };

    P._createChipElement = function(data, idx) {
        var self = this;
        var h = this._escapeHtml;
        var $chip = $('<span class="promode-chip"></span>').data('chip-index', idx);

        if (data.type === 'text') {
            if (data.isParen) {
                // Parentheses: plain text, no chip styling
                $chip.addClass('promode-paren-text');
            } else {
                $chip.addClass('promode-chip-term');
            }
            var label = data.isParen ? h(data.value) :
                       (data.quoted ? ("'" + h(data.value) + "'") :
                       (data.prefixSearch ? h(data.value) + ':*' : h(data.value)));
            $chip.append($('<span class="promode-chip-label"></span>').text(data.isParen ? data.value : '').html(data.isParen ? h(data.value) : label));
            if (data.slashModifier) {
                $chip.append($('<span class="promode-slash-badge"></span>').text(data.slashModifier));
            }
            if (!data.isParen) {
                $chip.append($('<span class="promode-chip-delete">&times;</span>'));
            }
        } else if (data.type === 'command') {
            $chip.addClass('promode-chip-command promode-command-' + (data.category || 'taxon'));

            if (data.key === 'R::') {
                $chip.addClass('promode-chip-multislot');
                $chip.append($('<span class="promode-cmd-badge-key"></span>').text(data.key));
                var $sg = $('<span class="promode-slot-group"></span>');
                $sg.append($('<span class="promode-cmd-badge-value"></span>').text('lng='));
                $sg.append($('<input class="promode-cmd-slot" size="4">').val(this._extractRParam(data.value, 'lng')));
                $sg.append($('<span class="promode-slot-sep">;</span>'));
                $sg.append($('<span class="promode-cmd-badge-value"></span>').text('lat='));
                $sg.append($('<input class="promode-cmd-slot" size="4">').val(this._extractRParam(data.value, 'lat')));
                $sg.append($('<span class="promode-slot-sep">;</span>'));
                $sg.append($('<span class="promode-cmd-badge-value"></span>').text('r='));
                $sg.append($('<input class="promode-cmd-slot" size="5">').val(this._extractRParam(data.value, 'r')));
                $chip.append($sg);
            } else if (data.key === 'T::') {
                $chip.addClass('promode-chip-multislot');
                var parts = (data.value || '').split('-');
                $chip.append($('<span class="promode-cmd-badge-key"></span>').text(data.key));
                var $sg2 = $('<span class="promode-slot-group"></span>');
                $sg2.append($('<span class="promode-cmd-badge-value"></span>'));
                $sg2.append($('<input class="promode-cmd-slot" size="5">').val(parts[0] || ''));
                $sg2.append($('<span class="promode-slot-sep">-</span>'));
                $sg2.append($('<input class="promode-cmd-slot" size="5">').val(parts[1] || ''));
                $sg2.append($('<span class="promode-cmd-badge-value"></span>'));
                $chip.append($sg2);
            } else {
                $chip.append($('<span class="promode-cmd-badge-key"></span>').text(data.key));
                $chip.append($('<span class="promode-cmd-badge-value"></span>').text(data.value || ''));
            }
            $chip.append($('<span class="promode-chip-delete">&times;</span>'));

            // Slot input change handlers
            $chip.on('change input', 'input.promode-cmd-slot', function() {
                var $c = $(this).closest('.promode-chip');
                var i = $c.data('chip-index');
                if (i === undefined || !self.chips[i]) return;
                if (self.chips[i].key === 'R::') {
                    var lng = $c.find('input.promode-cmd-slot').eq(0).val() || '';
                    var lat = $c.find('input.promode-cmd-slot').eq(1).val() || '';
                    var r = $c.find('input.promode-cmd-slot').eq(2).val() || '';
                    self.chips[i].value = 'lng=' + lng + ';lat=' + lat + ';r=' + r;
                } else if (self.chips[i].key === 'T::') {
                    var s = $c.find('input.promode-cmd-slot').eq(0).val() || '';
                    var e = $c.find('input.promode-cmd-slot').eq(1).val() || '';
                    self.chips[i].value = s + '-' + e;
                }
            });
        }

        // Chip click to select
        $chip.on('click', function(e) {
            if ($(e.target).hasClass('promode-chip-delete')) return;
            if ($(e.target).hasClass('promode-cmd-slot')) return;
            var i = $(this).data('chip-index');
            if (self._canEditChip(i)) {
                self._startEditingChip(i);
            } else {
                self._selectChip(i);
            }
        });

        return $chip;
    };

    P._formatDistanceOperator = function(op) {
        if (op === '<->') return '..';
        var m = String(op || '').match(/^<(\d+)>$/);
        return m ? '..' + m[1] : op;
    };

    P._createOperatorElement = function(opText, index) {
        var self = this;
        var label = this._operatorLabel(opText);
        var $op = $('<span class="promode-operator-chip promode-operator-swappable"></span>')
            .attr('role', 'button')
            .attr('tabindex', '0')
            .attr('title', 'Click to choose a different operator')
            .attr('aria-label', 'Choose a different operator for ' + label)
            .data('operator-index', index);
        $op.append($('<span class="promode-op-label"></span>').text(label));
        $op.on('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            self._showOperatorSelectionTools(index);
        });
        $op.on('keydown', function(e) {
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                e.stopPropagation();
                self._showOperatorSelectionTools(index);
            }
        });
        return $op;
    };

    P._createDistanceOperatorElement = function(opText, index) {
        var self = this;
        var $op = $('<span class="promode-followedby-chip promode-operator-swappable"></span>')
            .attr('role', 'button')
            .attr('tabindex', '0')
            .attr('title', 'Click to choose a different operator; edit the number field to change distance')
            .attr('aria-label', 'Choose a different ordered-distance operator')
            .data('operator-index', index);
        if (/^<\d+>$/.test(opText)) {
            var distance = String(opText).replace(/[<>]/g, '');
            var $distance = $('<span class="promode-distance-editor"></span>');
            $distance.append($('<span class="promode-distance-prefix"></span>').text('..'));
            $distance.append($('<input type="number" min="1" step="1" class="promode-distance-input" aria-label="Distance">').val(distance));
            $op.append($distance);
        } else {
            $op.append($('<span class="promode-op-label"></span>').text(this._formatDistanceOperator(opText)));
        }
        $op.on('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            self._showOperatorSelectionTools(index);
        });
        $op.on('keydown', function(e) {
            if ($(e.target).hasClass('promode-distance-input')) return;
            if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                e.stopPropagation();
                self._showOperatorSelectionTools(index);
            }
        });
        $op.on('input change', '.promode-distance-input', function(e) {
            e.stopPropagation();
            self._setDistanceOperator(index, $(this).val());
        });
        $op.on('mousedown click', '.promode-distance-input', function(e) {
            e.stopPropagation();
        });
        $op.on('keydown', '.promode-distance-input', function(e) {
            e.stopPropagation();
            if (e.key === 'Enter') {
                e.preventDefault();
                self._setDistanceOperator(index, $(this).val());
                $(this).blur();
            }
        });
        return $op;
    };

    P._setDistanceOperator = function(index, rawValue) {
        var value = parseInt(rawValue, 10);
        if (!Number.isFinite(value) || value < 1) value = 1;
        this.operators[index] = '<' + value + '>';
    };

    P._setSelectedOperator = function(op) {
        if (this._operatorSelectionIndex < 0) return;
        this.operators[this._operatorSelectionIndex] = op;
        this._operatorSelectionIndex = -1;
        this._rerender();
        this._restoreFullToolbar();
        this._focusEditorSoon();
    };

    P._operatorLabel = function(op) {
        if (op === 'AND') return this.labels.and;
        if (op === 'OR') return this.labels.or;
        if (op === 'NOT') return this.labels.not;
        if (op === 'AND_NOT') return this.labels.and + ' ' + this.labels.not;
        if (op === 'OR_NOT') return this.labels.or + ' ' + this.labels.not;
        if (op === '<') return 'LESS';
        if (op === '<=') return 'LESS OR EQUAL';
        if (op === '>') return 'GREATER';
        if (op === '>=') return 'GREATER OR EQUAL';
        if (op === '=') return 'EQUAL';
        if (op === '!=') return 'NOT EQUAL';
        return op;
    };

    P._isComparisonOperator = function(op) {
        return op === '<' || op === '<=' || op === '>' || op === '>=' || op === '=' || op === '!=';
    };

    P._extractRParam = function(value, param) {
        var m = (value || '').match(new RegExp(param + '=([^;]*)'));
        return m ? m[1] : '';
    };

    P._selectChip = function(idx) {
        this.$chipRow.find('.promode-chip').removeClass('promode-chip-selected');
        var $chip = this.$chipRow.find('.promode-chip[data-chip-index="' + idx + '"]');
        $chip.addClass('promode-chip-selected');
        this.$textInput.focus();
    };

    P._canEditChip = function(idx) {
        var chip = this.chips[idx];
        return !!(chip && chip.type === 'text' && !chip.isParen);
    };

    P._chipToEditableText = function(chip) {
        if (!chip) return '';
        var text = String(chip.value || '');
        if (chip.prefixSearch) text += ':*';
        if (chip.slashModifier) text += chip.slashModifier;
        return text;
    };

    P._startEditingChip = function(idx) {
        if (!this._canEditChip(idx)) return false;
        this._finishEditingChip(true);
        this._editingIndex = idx;
        this._insertionIndex = idx;
        this._editingChip = $.extend({}, this.chips[idx]);
        this.$textInput.val(this._chipToEditableText(this._editingChip));
        this._suppressEditBlur = true;
        this._rerender();
        this.$textInput.focus();
        this._suppressEditBlur = false;
        var input = this.$textInput[0];
        if (input && input.setSelectionRange) {
            var len = this.$textInput.val().length;
            input.setSelectionRange(0, len);
        }
        return true;
    };

    P._finishEditingChip = function(commit) {
        if (this._editingIndex < 0) return;
        var idx = this._editingIndex;
        var original = this._editingChip;
        var raw = String(this.$textInput.val() || '').trim();

        this._editingIndex = -1;
        this._editingChip = null;

        if (!commit) {
            this.$textInput.val('');
            this._insertionIndex = null;
            this._rerender();
            return;
        }

        if (!raw) {
            this.chips.splice(idx, 1);
        } else {
            this.chips[idx] = this._editableTextToChip(raw, original);
        }

        this._insertionIndex = null;
        this.$textInput.val('');
        this._rerender();
    };

    P._editableTextToChip = function(raw, original) {
        var text = String(raw || '').trim();
        var slashModifier = this._extractSlashModifier(text);
        if (slashModifier) text = text.slice(0, -slashModifier.length);

        var prefixSearch = false;
        if (text.endsWith(':*')) {
            prefixSearch = true;
            text = text.slice(0, -2);
        }

        var q = text.charAt(0);
        var quoted = this._autoquote;
        if ((q === "'" || q === '"') && text.charAt(text.length - 1) === q) {
            text = this._unquote(text);
            quoted = true;
        }

        return {
            type: 'text',
            value: text,
            quoted: quoted && !prefixSearch && !slashModifier,
            prefixSearch: prefixSearch,
            slashModifier: slashModifier || null
        };
    };

    P._moveEditorToTextChip = function(direction) {
        var idx = direction < 0 ? this.chips.length - 1 : 0;
        if (this._editingIndex >= 0) idx = this._editingIndex + direction;

        while (idx >= 0 && idx < this.chips.length) {
            if (this._canEditChip(idx)) return this._startEditingChip(idx);
            idx += direction;
        }
        return false;
    };

    P._cursorAtInputEdge = function(edge) {
        var input = this.$textInput[0];
        if (!input) return true;
        if (edge === 'start') return input.selectionStart === 0 && input.selectionEnd === 0;
        var len = this.$textInput.val().length;
        return input.selectionStart === len && input.selectionEnd === len;
    };

    /* ========== KEYBOARD HANDLING ========== */
    P._onFocusedInputKeyDown = function(e) {
        if (this._editingIndex >= 0) return;
        if (this.$textInput.val()) return;
        if (e.key === 'ArrowLeft') {
            e.preventDefault();
            this._moveEditorToTextChip(-1);
        } else if (e.key === 'ArrowRight') {
            e.preventDefault();
            this._moveEditorToTextChip(1);
        }
    };

    P._onKeyDown = function(e) {
        var val = this.$textInput.val();

        if (e.key === 'Alt' && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
            e.preventDefault();
            this._toggleAutoquote();
            return;
        }

        if (this._operatorSelectionIndex >= 0 && e.key === 'Escape') {
            e.preventDefault();
            this._operatorSelectionIndex = -1;
            this._restoreFullToolbar();
            return;
        }

        if (this._editingIndex >= 0) {
            if (e.key === 'Enter') {
                e.preventDefault();
                this._finishEditingChip(true);
                this.$textInput.focus();
                return;
            }
            if (e.key === 'Escape') {
                e.preventDefault();
                this._finishEditingChip(false);
                return;
            }
            if (e.key === 'ArrowLeft' && this._cursorAtInputEdge('start')) {
                e.preventDefault();
                this._finishEditingChip(true);
                this.$textInput.focus();
                this._moveEditorToTextChip(-1);
                return;
            }
            if (e.key === 'ArrowRight' && this._cursorAtInputEdge('end')) {
                e.preventDefault();
                this._finishEditingChip(true);
                this.$textInput.focus();
                this._moveEditorToTextChip(1);
                return;
            }
        }

        if (this._editingIndex < 0 && !val && e.key === 'ArrowLeft') {
            e.preventDefault();
            this._moveEditorToTextChip(-1);
            return;
        }
        if (this._editingIndex < 0 && !val && e.key === 'ArrowRight') {
            e.preventDefault();
            this._moveEditorToTextChip(1);
            return;
        }

        // Space commits
        if (e.key === ' ' && val.trim() && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
            e.preventDefault();
            this._commitText(val.trim());
            this._hideEnumRow();
            this._commandMode = null;
            this._hideCommandIndicator();
            return;
        }

        // Backspace on empty input removes last chip
        if (e.key === 'Backspace' && !val && this.chips.length > 0) {
            e.preventDefault();
            var removeIdx = this._editingIndex >= 0 ? this._editingIndex : this.chips.length - 1;
            this._editingIndex = -1;
            this._editingChip = null;
            this._removeChip(removeIdx);
            return;
        }

        // Alt+key shortcuts
        if (e.altKey && !e.ctrlKey && !e.metaKey) {
            if (e.key === 'r' || e.key === 'R') { e.preventDefault(); this._showCommandTools('taxon'); return; }
            if (e.key === 'l' || e.key === 'L') { e.preventDefault(); this._showCommandTools('location'); return; }
            if (e.key === 't' || e.key === 'T') { e.preventDefault(); this._showCommandTools('time'); return; }
            if (e.key === 'm' || e.key === 'M') { e.preventDefault(); this._showModifierTools(); return; }
            if (e.key === 'ArrowDown') { e.preventDefault(); this._navigateToolbarChips(1); return; }
            if (e.key === 'ArrowUp') { e.preventDefault(); this._navigateToolbarChips(-1); return; }
            if (e.key === 'Escape') { e.preventDefault(); this._hideToolbar(true); this._hideEnumRow(); return; }
            if (e.key === 'Enter') {
                e.preventDefault();
                var $focused = this.$toolbar.find('.promode-toolbar-chip.promode-tb-focused');
                if ($focused.length > 0) { $focused.click(); }
                return;
            }
        }

        // Escape clears all
        if (e.key === 'Escape' && !e.altKey) { this.clear(); return; }

        // Direct parentheses typing
        if ((e.key === '(' || e.key === ')') && !val) {
            e.preventDefault();
            this._addChip({ type: 'text', value: e.key, isParen: true });
            return;
        }

        // Direct operator typing on empty input
        if ((e.key === '&' || e.key === '|' || e.key === '!') && !val) {
            e.preventDefault();
            this._insertOperatorBySymbol(e.key);
            return;
        }
    };

    P._onKeyUp = function() {
        this._detectCommandMode(this.$textInput.val());
        this._renderShortcutBar();
    };

    P._onInput = function() {
        var val = this.$textInput.val();
        this._detectCommandMode(val);
        if (!this._commandMode) {
            this._hideEnumRow();
            this._scheduleToolbarUpdate();
        }
    };

    P._detectCommandMode = function(val) {
        var prefix = null;
        for (var i = 0; i < ALL_COMMANDS.length; i++) {
            if (val.indexOf(ALL_COMMANDS[i]) === 0) { prefix = ALL_COMMANDS[i]; break; }
        }
        if (prefix) {
            var cat = CMD_CATEGORY[prefix];
            if (this._commandMode !== prefix) {
                this._commandMode = prefix;
                this._showCommandIndicator(prefix, cat);
                this._showEnumRowFor(prefix);
                this._renderShortcutBar('command');
            }
        } else {
            if (this._commandMode) {
                this._commandMode = null;
                this._hideCommandIndicator();
                this._hideEnumRow();
                this._renderShortcutBar('text');
            }
        }
    };

    P._showCommandIndicator = function(prefix, cat) {
        var label = TAXON_RANKS[prefix] || TIME_CODES[prefix] || LOC_CODES[prefix] || '';
        this.$cmdIndicator
            .removeClass(function(i, c) { return (c.match(/promode-cmd-hint-\w+/) || []).join(' '); })
            .addClass('promode-cmd-hint-' + cat)
            .text(label)
            .show();
    };

    P._hideCommandIndicator = function() {
        this.$cmdIndicator.hide();
    };

    P._toggleAutoquote = function() {
        this._autoquote = !this._autoquote;
        this._renderShortcutBar('text');
    };

    /* ========== TEXT COMMIT ========== */
    P._commitText = function(text) {
        if (!text) return;

        // Operators: NOT chips, stored as inline text between chips
        if (text === '&') {
            if (this.chips.length > 0) {
                this.operators[this.chips.length - 1] = 'AND';
            }
            this.$textInput.val('');
            this._rerender();
            this._focusEditorSoon();
            return;
        }
        if (text === '|') {
            if (this.chips.length > 0) {
                this.operators[this.chips.length - 1] = 'OR';
            }
            this.$textInput.val('');
            this._rerender();
            this._focusEditorSoon();
            return;
        }
        if (text === '!') {
            if (this.chips.length === 0) {
                this._leadingNot = true;
            } else if (this.operators[this.chips.length - 1] === 'AND') {
                this.operators[this.chips.length - 1] = 'AND_NOT';
            } else if (this.operators[this.chips.length - 1] === 'OR') {
                this.operators[this.chips.length - 1] = 'OR_NOT';
            } else {
                this.operators[this.chips.length - 1] = 'NOT';
            }
            this.$textInput.val('');
            this._rerender();
            this._focusEditorSoon();
            return;
        }
        if (text === '<->' || /^<\d+>$/.test(text) || this._isComparisonOperator(text)) {
            if (this.chips.length > 0) {
                this.operators[this.chips.length - 1] = text;
            }
            this.$textInput.val('');
            this._rerender();
            this._focusEditorSoon();
            return;
        }

        // Parentheses as text tokens
        if (text === '(' || text === ')') {
            this._addChip({ type: 'text', value: text, isParen: true });
            this.$textInput.val('');
            return;
        }

        // Command chips
        var cmdPrefix = null;
        for (var i = 0; i < ALL_COMMANDS.length; i++) {
            if (text.indexOf(ALL_COMMANDS[i]) === 0) { cmdPrefix = ALL_COMMANDS[i]; break; }
        }
        if (cmdPrefix) {
            var cat = CMD_CATEGORY[cmdPrefix];
            this._addChip({ type: 'command', key: cmdPrefix, value: text.substring(cmdPrefix.length), category: cat });
            this.$textInput.val('');
            this._commandMode = null;
            this._hideCommandIndicator();
            this._hideEnumRow();
            return;
        }

        var slashModifier = this._extractSlashModifier(text);
        if (slashModifier) {
            this._addChip({
                type: 'text',
                value: text.slice(0, -slashModifier.length),
                slashModifier: slashModifier,
                quoted: false
            });
            this.$textInput.val('');
            this._commandMode = null;
            this._hideCommandIndicator();
            this._hideEnumRow();
            return;
        }

        if (!this._autoquote && /\s+/.test(text)) {
            this._commitUnquotedTextParts(text);
        } else {
            this._addChip({ type: 'text', value: text, quoted: this._autoquote });
        }
        this.$textInput.val('');
        this._commandMode = null;
        this._hideCommandIndicator();
        this._hideEnumRow();
    };

    P._commitUnquotedTextParts = function(text) {
        var parts = String(text || '').trim().split(/\s+/).filter(Boolean);
        for (var i = 0; i < parts.length; i++) {
            this._addChip({ type: 'text', value: parts[i], quoted: false });
        }
    };

    P._insertOperatorBySymbol = function(sym) {
        if (sym === '&') {
            if (this.chips.length > 0) {
                this.operators[this.chips.length - 1] = 'AND';
            }
        } else if (sym === '|') {
            if (this.chips.length > 0) {
                this.operators[this.chips.length - 1] = 'OR';
            }
        } else if (sym === '!') {
            if (this.chips.length === 0) {
                this._leadingNot = true;
            } else {
                this.operators[this.chips.length - 1] = 'NOT';
            }
        }
        this._rerender();
    };

    /* ========== PASTE ========== */
    P._onPaste = function(e) {
        var self = this;
        var clipboardData = e.originalEvent.clipboardData;
        if (!clipboardData) return;
        var pasted = clipboardData.getData('text/plain');
        if (!pasted || !pasted.trim()) return;

        // Multi-slot paste
        var $focused = this.$chipRow.find('.promode-chip-multislot input.promode-cmd-slot:focus');
        if ($focused.length > 0) {
            e.preventDefault();
            var $parent = $focused.closest('.promode-chip');
            var i = $parent.data('chip-index');
            if (i !== undefined && this.chips[i] && this.chips[i].key === 'R::') {
                var rp = this._parseRParams(pasted.trim());
                var $slots = $parent.find('input.promode-cmd-slot');
                if (rp.lng) $slots.eq(0).val(rp.lng);
                if (rp.lat) $slots.eq(1).val(rp.lat);
                if (rp.r) $slots.eq(2).val(rp.r);
                this.chips[i].value = 'lng=' + ($slots.eq(0).val()||'') + ';lat=' + ($slots.eq(1).val()||'') + ';r=' + ($slots.eq(2).val()||'');
                $slots.trigger('input');
                return;
            }
            if (i !== undefined && this.chips[i] && this.chips[i].key === 'T::') {
                var parts = pasted.trim().split(/[\s-]+/);
                var $slots2 = $parent.find('input.promode-cmd-slot');
                if (parts.length >= 2) { $slots2.eq(0).val(parts[0]); $slots2.eq(1).val(parts[1]); }
                else if (parts.length === 1) $slots2.eq(0).val(parts[0]);
                this.chips[i].value = ($slots2.eq(0).val()||'') + '-' + ($slots2.eq(1).val()||'');
                $slots2.trigger('input');
                return;
            }
            return;
        }

        e.preventDefault();
        this._consumeQueryTokens(this._tokenizeQuery(pasted), true);
        this.$textInput.val('');
        this._rerender();
    };

    P._parseRParams = function(val) {
        var out = { lng: '', lat: '', r: '' };
        var parts = val.split(/[;\s]+/);
        for (var i = 0; i < parts.length; i++) {
            var m = parts[i].match(/^(lng|lat|r)\s*[=:]\s*(.+)$/i);
            if (m) out[m[1].toLowerCase()] = m[2];
        }
        return out;
    };

    /* ========== TOOLBAR ========== */
    P._operatorToolbarItems = function() {
        return [
            { label: this.labels.and, kbd: '&', cls: 'promode-tb-operator', insert: '&', operatorValue: 'AND' },
            { label: this.labels.or, kbd: '|', cls: 'promode-tb-operator', insert: '|', operatorValue: 'OR' },
            { label: this.labels.not, kbd: '!', cls: 'promode-tb-operator', insert: '!', operatorValue: 'NOT' },
            { label: this.labels.and + ' ' + this.labels.not, kbd: '& !', cls: 'promode-tb-operator', insert: '& !', operatorValue: 'AND_NOT' },
            { label: this.labels.or + ' ' + this.labels.not, kbd: '| !', cls: 'promode-tb-operator', insert: '| !', operatorValue: 'OR_NOT' },
            { label: 'LESS', kbd: '<', cls: 'promode-tb-operator', insert: '<', operatorValue: '<' },
            { label: 'LESS OR EQUAL', kbd: '<=', cls: 'promode-tb-operator', insert: '<=', operatorValue: '<=' },
            { label: 'GREATER', kbd: '>', cls: 'promode-tb-operator', insert: '>', operatorValue: '>' },
            { label: 'GREATER OR EQUAL', kbd: '>=', cls: 'promode-tb-operator', insert: '>=', operatorValue: '>=' },
            { label: 'EQUAL', kbd: '=', cls: 'promode-tb-operator', insert: '=', operatorValue: '=' },
            { label: 'NOT EQUAL', kbd: '!=', cls: 'promode-tb-operator', insert: '!=', operatorValue: '!=' },
            { label: this.labels.followedBy, kbd: '<->', cls: 'promode-tb-modifier', insert: '<->', operatorValue: '<->' },
            { label: this.labels.distance, kbd: '<N>', cls: 'promode-tb-modifier', insert: '<3>', operatorValue: '<3>' }
        ];
    };

    P._modifierToolbarItems = function() {
        return SLASH_MODIFIERS.map(function(item) {
            return {
                label: item.label,
                kbd: item.code,
                cls: 'promode-tb-modifier',
                insert: item.code,
                slashModifier: true
            };
        });
    };

    P._buildFullToolbar = function() {
        var self = this;
        this.$toolbar.empty();

        // Row 1: Operators
        this.$toolbar.append($('<span class="promode-toolbar-label">Operators</span>'));
        this.$toolbar.append(this._buildChipRow(this._operatorToolbarItems()));

        // Row 2: Taxonomy
        this.$toolbar.append($('<span class="promode-toolbar-label">Taxonomy</span>'));
        this.$toolbar.append(this._buildChipRow([
            { label: 'Kingdom', cls: 'promode-tb-taxon', insert: 'K::' },
            { label: 'Phylum', cls: 'promode-tb-taxon', insert: 'P::' },
            { label: 'Class', cls: 'promode-tb-taxon', insert: 'C::' },
            { label: 'Order', cls: 'promode-tb-taxon', insert: 'O::' },
            { label: 'Family', cls: 'promode-tb-taxon', insert: 'F::' },
            { label: 'Genus', cls: 'promode-tb-taxon', insert: 'G::' },
            { label: 'Species', cls: 'promode-tb-taxon', insert: 'S::' }
        ]));

        // Row 3: Location
        this.$toolbar.append($('<span class="promode-toolbar-label">Location</span>'));
        this.$toolbar.append(this._buildChipRow([
            { label: 'Feature Class', cls: 'promode-tb-location', insert: 'LOC::' },
            { label: 'Radius', cls: 'promode-tb-location', insert: 'R::' }
        ]));

        // Row 4: Time
        this.$toolbar.append($('<span class="promode-toolbar-label">Time</span>'));
        this.$toolbar.append(this._buildChipRow([
            { label: 'Year', cls: 'promode-tb-time', insert: 'Y::' },
            { label: 'Month', cls: 'promode-tb-time', insert: 'M::' },
            { label: 'Day', cls: 'promode-tb-time', insert: 'D::' },
            { label: 'Season', cls: 'promode-tb-time', insert: 'E::' },
            { label: 'Year Range', cls: 'promode-tb-time', insert: 'T::' }
        ]));

        this.$toolbar.append($('<span class="promode-toolbar-label">Modifiers</span>'));
        this.$toolbar.append(this._buildChipRow(this._modifierToolbarItems()));
    };

    P._showFullToolbar = function() {
        this._hideEnumRow();
        this.showPersistentToolbar();
    };

    P._showCommandTools = function(category) {
        var commands = PROMODE_COMMANDS[category];
        if (!commands) return;
        this._hideEnumRow();
        this.$toolbar.empty();
        var label = category.charAt(0).toUpperCase() + category.slice(1);

        this.$toolbar.append($('<span class="promode-toolbar-label">' + label + '</span>'));

        var items = [];
        for (var i = 0; i < commands.length; i++) {
            var cmd = commands[i];
            var name = TAXON_RANKS[cmd] || TIME_CODES[cmd] || LOC_CODES[cmd] || cmd;
            items.push({ label: name, cls: 'promode-tb-' + category, insert: cmd });
        }
        this.$toolbar.append(this._buildChipRow(items));
        this.$toolbar.addClass('promode-toolbar-visible');
        this._syncDetachedHostVisibility();
        var $chips = this.$toolbar.find('.promode-toolbar-chip');
        if ($chips.length > 0) $chips.first().addClass('promode-tb-focused');
    };

    P._showOperatorTools = function() {
        this._hideEnumRow();
        this.$toolbar.empty();
        this.$toolbar.append($('<span class="promode-toolbar-label">Operators</span>'));
        this.$toolbar.append(this._buildChipRow(this._operatorToolbarItems()));
        this.$toolbar.addClass('promode-toolbar-visible');
        this._syncDetachedHostVisibility();
    };

    P._showOperatorSelectionTools = function(index) {
        this._operatorSelectionIndex = index;
        this._hideEnumRow();
        this.$toolbar.empty();
        this.$toolbar.append($('<span class="promode-toolbar-label">Swap</span>'));
        this.$toolbar.append(this._buildChipRow(this._operatorToolbarItems()));
        this.$toolbar.addClass('promode-toolbar-visible promode-toolbar-operator-select');
        this._renderShortcutBar('operator');
        this._syncDetachedHostVisibility();
    };

    P._showModifierTools = function() {
        this._hideEnumRow();
        this.$toolbar.empty();
        this.$toolbar.append($('<span class="promode-toolbar-label">Modifiers</span>'));
        this.$toolbar.append(this._buildChipRow(this._modifierToolbarItems()));
        this.$toolbar.addClass('promode-toolbar-visible');
        this._syncDetachedHostVisibility();
        var $chips = this.$toolbar.find('.promode-toolbar-chip');
        if ($chips.length > 0) $chips.first().addClass('promode-tb-focused');
    };

    P._buildChipRow = function(items) {
        var self = this;
        var $row = $('<div class="promode-toolbar-chips"></div>');
        items.forEach(function(item) {
            var $chip = $('<span class="promode-toolbar-chip ' + item.cls + '"></span>').text(item.label);
            if (item.kbd) {
                $chip.append($('<span class="promode-kbd-badge"></span>').text(item.kbd));
            }
            $chip.on('click', function() {
                self._insertFromToolbar(item);
            });
            $row.append($chip);
        });
        return $row;
    };

    P._insertFromToolbar = function(item) {
        var val = this.$textInput.val();
        if (this._operatorSelectionIndex >= 0 && item.operatorValue) {
            this._setSelectedOperator(item.operatorValue);
            return;
        }
        if (item.slashModifier) {
            this._applySlashModifier(item.insert);
            return;
        }
        if (val.trim()) this._commitText(val.trim());

        if (ALL_COMMANDS.indexOf(item.insert) >= 0) {
            this.$textInput.val(item.insert).focus();
            this._detectCommandMode(item.insert);
        } else {
            this._commitText(item.insert);
        }
    };

    P._applySlashModifier = function(modifier) {
        var val = this.$textInput.val();
        if (val.trim()) {
            var base = val.trim().replace(/\/[a-z]$/i, '');
            this.$textInput.val(base + modifier).focus();
            return;
        }

        for (var i = this.chips.length - 1; i >= 0; i--) {
            var chip = this.chips[i];
            if (chip.type === 'text' && !chip.isParen) {
                chip.slashModifier = modifier;
                this._rerender();
                this.$textInput.focus();
                return;
            }
        }

        this.$textInput.val(modifier).focus();
    };

    P._scheduleToolbarUpdate = function() {
        var self = this;
        if (this._toolbarTimeout) clearTimeout(this._toolbarTimeout);
        this._toolbarTimeout = setTimeout(function() {
            if (!self._commandMode && self.$textInput.is(':focus')) {
                self._showFullToolbar();
            }
        }, 140);
    };

    P._hideToolbar = function(force) {
        if (!force && this.$root.is(':visible')) {
            this._ensureToolbarOpen();
            return;
        }
        this.$toolbar.removeClass('promode-toolbar-visible');
        this._syncDetachedHostVisibility();
    };

    P._ensureToolbarOpen = function() {
        if (!this.$root.is(':visible')) return;
        this.showPersistentToolbar();
    };

    P._navigateToolbarChips = function(dir) {
        var $chips = this.$toolbar.find('.promode-toolbar-chip');
        if ($chips.length === 0) return;
        var $focused = this.$toolbar.find('.promode-toolbar-chip.promode-tb-focused');
        var idx = $focused.length ? $chips.index($focused) : (dir > 0 ? -1 : $chips.length);
        $chips.removeClass('promode-tb-focused');
        var newIdx = dir > 0 ? Math.min(idx + 1, $chips.length - 1) : Math.max(idx - 1, 0);
        $chips.eq(newIdx).addClass('promode-tb-focused');
    };

    /* ========== ENUM ROW ========== */
    P._showEnumRowFor = function(prefix) {
        var self = this;
        this.$enumRow.empty();

        if (prefix === 'M::') {
            MONTHS.forEach(function(m) {
                $('<span class="promode-enum-chip"></span>').text(m).on('click', function() {
                    self._commitText(prefix + m);
                    self._hideEnumRow();
                }).appendTo(self.$enumRow);
            });
        } else if (prefix === 'E::') {
            SEASONS.forEach(function(s) {
                $('<span class="promode-enum-chip"></span>').text(s).on('click', function() {
                    self._commitText(prefix + s);
                    self._hideEnumRow();
                }).appendTo(self.$enumRow);
            });
        } else if (prefix === 'D::') {
            for (var d = 1; d <= 31; d++) {
                (function(day) {
                    $('<span class="promode-enum-chip"></span>').text(String(day)).on('click', function() {
                        self._commitText(prefix + day);
                        self._hideEnumRow();
                    }).appendTo(self.$enumRow);
                })(d);
            }
        } else if (prefix === 'Y::') {
            $('<span class="promode-enum-chip"></span>').text('type year number...').on('click', function() {
                self.$textInput.val(prefix).focus();
            }).appendTo(self.$enumRow);
        } else if (prefix === 'LOC::') {
            $('<span class="promode-enum-chip"></span>').text('type feature class + code...').on('click', function() {
                self.$textInput.val(prefix).focus();
            }).appendTo(self.$enumRow);
        } else if (prefix === 'R::') {
            $('<span class="promode-enum-chip"></span>').text('lng=... ;lat=... ;r=... (radius in meters)').on('click', function() {
                self.$textInput.val(prefix).focus();
            }).appendTo(self.$enumRow);
        } else if (prefix === 'T::') {
            $('<span class="promode-enum-chip"></span>').text('type year range (e.g. 1880-1900)').on('click', function() {
                self.$textInput.val(prefix).focus();
            }).appendTo(self.$enumRow);
        }

        if (this.$enumRow.children().length > 0) {
            this.$enumRow.addClass('promode-enum-visible');
        }
        this._syncDetachedHostVisibility();
    };

    P._hideEnumRow = function() {
        this.$enumRow.removeClass('promode-enum-visible').empty();
        this._syncDetachedHostVisibility();
    };

    /* ========== MOUSE HANDLING ========== */
    P._onChipRowMouseMove = function(e) {
        this._ensureToolbarOpen();
    };

    P._onContainerClick = function(e) {
        if (e.altKey && !e.shiftKey && !e.ctrlKey && !e.metaKey) {
            e.preventDefault();
            this._showFullToolbar();
            return;
        }
        // Focus text input when clicking the search bar background
        if ($(e.target).closest('.promode-chip').length === 0 &&
            $(e.target).closest('.promode-chip-delete').length === 0 &&
            $(e.target).closest('.promode-inline-editor').length === 0) {
            this.$textInput.focus();
        }
    };

    P._onChipMouseDown = function(e, chipEl) {
        var $target = $(chipEl);
        if (!$target.length) { this._dragMode = null; return; }

        if (e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
            e.preventDefault();
            this._dragMode = 'group';
            this._startDragSelect($target, 'group');
        } else if (e.ctrlKey && !e.shiftKey && !e.altKey && !e.metaKey) {
            e.preventDefault();
            this._dragMode = 'join';
            this._startDragSelect($target, 'join');
        }
    };

    P._startDragSelect = function($target, mode) {
        var idx = $target.data('chip-index');
        if (idx === undefined) return;
        this._selStart = idx;
        this._selEnd = idx;
        this._updateSelectionVisual(mode);
    };

    P._updateSelectionVisual = function(mode) {
        var selCls = mode === 'group' ? 'promode-chip-selected' : 'promode-chip-join-selected';
        this.$chipRow.find('.promode-chip').removeClass('promode-chip-selected promode-chip-join-selected');
        var start = Math.min(this._selStart, this._selEnd);
        var end = Math.max(this._selStart, this._selEnd);
        this.$chipRow.find('.promode-chip').each(function() {
            var i = $(this).data('chip-index');
            if (i >= start && i <= end) $(this).addClass(selCls);
        });
        this._updateDragOverlay(mode);
    };

    P._updateDragOverlay = function(mode) {
        var $overlay = mode === 'group' ? this.$groupingOverlay : this.$joinOverlay;
        var selCls = mode === 'group' ? 'promode-chip-selected' : 'promode-chip-join-selected';
        var selected = this.$chipRow.find('.promode-chip.' + selCls);
        if (selected.length > 0) {
            var firstRect = selected.first()[0].getBoundingClientRect();
            var lastRect = selected.last()[0].getBoundingClientRect();
            var barRect = this.$chipRow[0].getBoundingClientRect();
            $overlay.css({
                left: (firstRect.left - barRect.left) + 'px',
                top: (firstRect.top - barRect.top) + 'px',
                width: (lastRect.right - firstRect.left) + 'px',
                height: (lastRect.bottom - firstRect.top) + 'px',
                display: 'block'
            });
        }
    };

    P._onGlobalMouseMove = function(e) {
        if (!this._dragMode) return;
        var $chips = this.$chipRow.find('.promode-chip');
        var nearbyIdx = -1;
        $chips.each(function() {
            var r = this.getBoundingClientRect();
            if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) {
                nearbyIdx = $(this).data('chip-index');
                return false;
            }
        });
        if (nearbyIdx >= 0 && nearbyIdx !== this._selEnd) {
            this._selEnd = nearbyIdx;
            this._updateSelectionVisual(this._dragMode);
        }
    };

    P._onGlobalMouseUp = function(e) {
        if (e.button === 2 && this._dragMode) { this._cancelDragSelect(); return; }

        if (this._dragMode === 'group' && this._selStart >= 0 && this._selEnd >= 0 && this._selStart !== this._selEnd) {
            this._applyGrouping();
        } else if (this._dragMode === 'join' && this._selStart >= 0 && this._selEnd >= 0 && this._selStart !== this._selEnd) {
            this._applyJoin();
        }

        this._cancelDragSelect();
    };

    P._cancelDragSelect = function() {
        this.$chipRow.find('.promode-chip').removeClass('promode-chip-selected promode-chip-join-selected');
        this.$groupingOverlay.css({ display: 'none' });
        this.$joinOverlay.css({ display: 'none' });
        this._dragMode = null;
        this._selStart = -1;
        this._selEnd = -1;
    };

    P._applyGrouping = function() {
        var start = Math.min(this._selStart, this._selEnd);
        var end = Math.max(this._selStart, this._selEnd);
        if (start === end || start < 0 || end >= this.chips.length) return;

        var before = this.chips.slice(0, start);
        var inner = this.chips.slice(start, end + 1);
        var after = this.chips.slice(end + 1);

        // Preserve operators: operators before `start` stay, operators between grouped chips stay,
        // operators after grouped chips need re-indexing.
        var beforeOps = this.operators.slice(0, start);
        var innerOps = this.operators.slice(start, end); // operators within the group
        var afterOps = this.operators.slice(end + 1);

        this.chips = before.concat(
            [{ type: 'text', value: '(', isParen: true }],
            inner,
            [{ type: 'text', value: ')', isParen: true }]
        ).concat(after);

        // Rebuild operators: beforeOps stay, innerOps stay (between inner chips), then the rest
        this.operators = beforeOps.concat(innerOps).concat(afterOps);
        // operator after closing paren before `after` chips: this was operators[end] which is now at position beforeOps.length + innerOps.length
        // Actually it's already in afterOps since we sliced from end+1

        this._rerender();
    };

    P._applyJoin = function() {
        var start = Math.min(this._selStart, this._selEnd);
        var end = Math.max(this._selStart, this._selEnd);
        if (start === end || start < 0 || end >= this.chips.length) return;

        // All selected chips must be text type
        for (var i = start; i <= end; i++) {
            if (this.chips[i].type !== 'text' || this.chips[i].isParen) return;
        }

        var texts = [];
        for (var j = start; j <= end; j++) {
            texts.push(this.chips[j].value);
        }
        var joined = texts.join(' ');

        var before = this.chips.slice(0, start);
        var after = this.chips.slice(end + 1);
        var beforeOps = this.operators.slice(0, start);
        // Remove operators within the joined range
        var afterOps = this.operators.slice(end + 1);

        this.chips = before.concat([{ type: 'text', value: joined, quoted: true }]).concat(after);
        this.operators = beforeOps.concat(afterOps);

        this._rerender();
    };

    /* ========== SYNTAX ERROR ========== */
    P.showError = function(msg) {
        this.$error.text(msg).addClass('promode-error-visible');
        this._syncDetachedHostVisibility();
    };

    P._hideError = function() {
        this.$error.removeClass('promode-error-visible').text('');
        this._syncDetachedHostVisibility();
    };

})(jQuery);
