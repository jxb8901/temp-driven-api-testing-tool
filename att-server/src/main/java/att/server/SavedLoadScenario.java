package att.server;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;

/** Stored private authored inputs, never an approval to execute or a Schedule source pointer. */
final class SavedLoadScenario {
    static final int MAX_AUTHORED_BYTES=256*1024;
    String id,ownerSubject,packageId,name,description,tagsJson,authoredJson,model,sourceRevision,createdAt,updatedAt;
    long revision;

    void validate() {
        if(id==null || !id.matches("LS[A-F0-9]{32}") || ownerSubject==null || ownerSubject.isBlank() || ownerSubject.length()>320
                || packageId==null || packageId.isBlank() || packageId.length()>64
                || name==null || name.isBlank() || name.length()>120 || description==null || description.length()>1000
                || tagsJson==null || tagsJson.getBytes(StandardCharsets.UTF_8).length>4096
                || authoredJson==null || authoredJson.getBytes(StandardCharsets.UTF_8).length>MAX_AUTHORED_BYTES
                || !("virtualUsers".equals(model)||"arrivalRate".equals(model)) || revision<1
                || createdAt==null || updatedAt==null || (sourceRevision!=null&&!sourceRevision.matches("[a-f0-9]{64}")))
            throw new IllegalArgumentException("Invalid Saved Scenario record");
        try {
            com.fasterxml.jackson.databind.JsonNode authored=ServerRuntime.JSON.readTree(authoredJson);
            com.fasterxml.jackson.databind.JsonNode tags=ServerRuntime.JSON.readTree(tagsJson);
            if(authored==null || !authored.isObject() || tags==null || !tags.isArray() || tags.size()>20)
                throw new IllegalArgumentException("Invalid Saved Scenario content");
            for(com.fasterxml.jackson.databind.JsonNode tag:tags)
                if(!tag.isTextual() || tag.asText().isBlank() || tag.asText().length()>64 || tag.asText().chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Invalid Saved Scenario tag");
        } catch(java.io.IOException invalid) { throw new IllegalArgumentException("Invalid Saved Scenario content",invalid); }
    }
    static SavedLoadScenario fromRow(ResultSet r) throws Exception {
        SavedLoadScenario v=new SavedLoadScenario();
        v.id=r.getString(1);v.ownerSubject=r.getString(2);v.packageId=r.getString(3);v.name=r.getString(4);
        v.description=r.getString(5);v.tagsJson=r.getString(6);v.authoredJson=r.getString(7);v.model=r.getString(8);
        v.sourceRevision=r.getString(9);v.revision=r.getLong(10);v.createdAt=r.getString(11);v.updatedAt=r.getString(12);
        return v;
    }
}
