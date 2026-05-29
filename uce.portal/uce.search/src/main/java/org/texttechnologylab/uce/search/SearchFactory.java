package org.texttechnologylab.uce.search;

import org.springframework.context.ApplicationContext;
import org.texttechnologylab.uce.common.models.search.SearchType;

public class SearchFactory {
    private final ApplicationContext context;

    public SearchFactory(ApplicationContext context) {
        this.context = context;
    }

    public Search forState(SearchState searchState) {
        if (searchState == null) {
            return new Search_DefaultImpl();
        }
        SearchType type = searchState.getSearchType();
        if (type == SearchType.SEMANTICROLE) {
            return new Search_SemanticRoleImpl();
        }
        if (type == SearchType.NEG) {
            return new SearchCompleteNegation();
        }
        return new Search_DefaultImpl();
    }

    public LayeredSearch layeredSearch(String searchId) {
        return new LayeredSearch(context, searchId);
    }
}
