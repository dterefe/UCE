package org.texttechnologylab.uce.web.render.spec;

import com.google.gson.JsonParser;
import junit.framework.TestCase;
import org.texttechnologylab.models.authentication.DocumentPermission;
import org.texttechnologylab.uce.common.models.corpus.Document;
import org.texttechnologylab.uce.common.models.corpus.MetadataTitleInfo;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadata;
import org.texttechnologylab.uce.common.models.corpus.UCEMetadataValueType;
import org.texttechnologylab.uce.web.render.RenderPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeclarativeSpecBinderTest extends TestCase {

    public void testMetadataAndDocumentRefs() throws Exception {
        var document = new Document("de-DE", "Doc Title", "doc-1", 1L);
        document.setMetadataTitleInfo(new MetadataTitleInfo());
        var metadata = new ArrayList<UCEMetadata>();
        metadata.add(meta("user_hash", "abc", UCEMetadataValueType.ENUM));
        metadata.add(meta("pages_count", "3", UCEMetadataValueType.NUMBER));
        metadata.add(meta("top_urls", "[{\"rank\":1,\"url\":\"https://example.com\"}]", UCEMetadataValueType.JSON));
        document.setUceMetadata(metadata);

        var binder = new DeclarativeSpecBinder(document, new RenderPrincipal("user1"));
        var model = binder.bindModel(JsonParser.parseString("""
                {
                  "title": { "$ref": "document.documentTitle" },
                  "userHash": { "$ref": "metadata.user_hash" },
                  "pages": { "$ref": "metadata.pages_count" },
                  "topUrls": { "$ref": "metadata.top_urls" }
                }
                """));

        assertEquals("Doc Title", model.get("title"));
        assertEquals("abc", model.get("userHash"));
        assertEquals(3.0, (Double) model.get("pages"));
        assertTrue(model.get("topUrls") instanceof List);
        assertEquals(1, ((List<?>) model.get("topUrls")).size());
        assertTrue(((List<?>) model.get("topUrls")).get(0) instanceof Map);
    }

    public void testEffectivePermissionRef() throws Exception {
        var document = new Document("de-DE", "Doc", "doc-2", 2L);

        var perm = new DocumentPermission();
        perm.setType(DocumentPermission.DOCUMENT_PERMISSION_TYPE.EFFECTIVE);
        perm.setLevel(DocumentPermission.DOCUMENT_PERMISSION_LEVEL.WRITE);
        perm.setName("user1");
        document.addPermission(perm);

        var binder = new DeclarativeSpecBinder(document, new RenderPrincipal("user1"));
        var model = binder.bindModel(JsonParser.parseString("""
                {
                  "effectivePermission": { "$ref": "effectivePermission" }
                }
                """));

        assertNotNull(model.get("effectivePermission"));
        assertTrue(model.get("effectivePermission") instanceof PermissionBadgeModel);
        var badge = (PermissionBadgeModel) model.get("effectivePermission");
        assertEquals(PermissionBadgeModel.PermissionLevel.WRITE, badge.level());
    }

    public void testSpecLoaderClasspath() throws Exception {
        var loader = new SpecLoader();
        var spec = loader.load("CLASSPATH::render-specs/feedback.json");
        assertNotNull(spec);
        assertNotNull(spec.getMiddle());
        assertEquals("feedback/middlePaneSpec.ftl", spec.getMiddle().getTemplate());
    }

    private static UCEMetadata meta(String key, String value, UCEMetadataValueType type) {
        var meta = new UCEMetadata();
        meta.setKey(key);
        meta.setValue(value);
        meta.setValueType(type);
        return meta;
    }
}
