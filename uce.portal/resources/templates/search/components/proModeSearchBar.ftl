<div class="promode-search-bar-container" id="proModeSearchBar"
     data-label-and="${languageResource.get('promodeOperatorAnd')?html}"
     data-label-or="${languageResource.get('promodeOperatorOr')?html}"
     data-label-not="${languageResource.get('promodeOperatorNot')?html}"
     data-label-followed-by="${languageResource.get('promodeOperatorFollowedBy')?html}"
     data-label-distance="${languageResource.get('promodeOperatorDistance')?html}">
    <div class="promode-search-bar w-100 rounded-0">
        <div class="promode-chip-row">
            <span class="promode-inline-editor">
                <input type="text" class="promode-text-input"
                       placeholder="${languageResource.get('searchPlaceholder')}"/>
                <span class="promode-command-indicator display-none"></span>
            </span>
        </div>
        <div class="promode-grouping-overlay"></div>
        <div class="promode-join-overlay"></div>
    </div>
    <div class="promode-shortcut-bar"></div>
    <div class="promode-toolbar"></div>
    <div class="promode-enum-dropdown"></div>
    <div class="promode-syntax-error"></div>
</div>
