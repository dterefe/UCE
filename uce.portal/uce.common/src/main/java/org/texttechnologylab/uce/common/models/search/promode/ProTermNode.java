package org.texttechnologylab.uce.common.models.search.promode;

public class ProTermNode extends ProQueryOperand {
    private final String value;
    private final boolean quoted;
    private final boolean prefixSearch;
    private final String slashModifier;

    public ProTermNode(String value, boolean quoted, boolean prefixSearch, String slashModifier, SourceSpan span) {
        super(span, value);
        this.value = value;
        this.quoted = quoted;
        this.prefixSearch = prefixSearch;
        this.slashModifier = slashModifier;
    }

    public String value() {
        return value;
    }

    public boolean quoted() {
        return quoted;
    }

    public boolean prefixSearch() {
        return prefixSearch;
    }

    public String slashModifier() {
        return slashModifier;
    }

    public boolean hasSlashModifier() {
        return slashModifier != null && !slashModifier.isBlank();
    }
}
