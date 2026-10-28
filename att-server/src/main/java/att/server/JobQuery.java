package att.server;

import java.time.Instant;
import java.util.*;

/** Validated SQL predicate inputs; no caller text is interpolated as SQL. */
final class JobQuery {
    int offset,limit=100;
    String principal,principalMode="all",actor,packageId,type,status,from,to,search,exactJobId,sort="newest",deletion="all";
    String submissionSource,executionPrincipal,executionPrincipalMode="all",scheduleOwner,scheduleOwnerMode="all",triggeredBy,triggeredByMode="all";
    boolean executionPrincipalExact,scheduleOwnerExact,triggeredByExact;
    boolean pinnedOnly,grouped;
    static JobQuery parse(Map<String,String> values,String actor) {
        JobQuery q=new JobQuery();q.actor=actor;
        q.offset=integer(values.get("offset"),0,0,Integer.MAX_VALUE);q.limit=integer(values.get("limit"),100,1,100);
        q.principal=bounded(values.get("principal"),120);q.principalMode=choice(values.get("principalMode"),"all","all","mine","schedule","text");
        q.submissionSource=choice(values.get("submissionSource"),null,"manual","scheduled");
        q.executionPrincipal=bounded(values.get("executionPrincipal"),256);q.executionPrincipalMode=choice(values.get("executionPrincipalMode"),q.executionPrincipal==null?"all":"text","all","mine","text");
        q.scheduleOwner=bounded(values.get("scheduleOwner"),256);q.scheduleOwnerMode=choice(values.get("scheduleOwnerMode"),q.scheduleOwner==null?"all":"text","all","mine","text");
        q.triggeredBy=bounded(values.get("triggeredBy"),256);q.triggeredByMode=choice(values.get("triggeredByMode"),q.triggeredBy==null?"all":"text","all","mine","text");
        q.executionPrincipalExact=bool(values.get("executionPrincipalExact"));q.scheduleOwnerExact=bool(values.get("scheduleOwnerExact"));q.triggeredByExact=bool(values.get("triggeredByExact"));
        q.packageId=bounded(values.get("packageId"),64);q.type=choice(values.get("executionType"),null,"run","debug","load","validate");
        q.status=choice(values.get("status"),null,"QUEUED","PREPARING","RUNNING","CANCEL_REQUESTED","PASS","FAIL","ERROR","INVALID","CANCELLED");
        q.sort=choice(values.get("sort"),"newest","newest","oldest","target-asc","target-desc");
        q.deletion=choice(values.get("deletion"),"all","all","pending","unmarked");
        q.search=bounded(values.get("search"),120);
        if(q.search!=null&&q.search.startsWith("job:")) {
            q.exactJobId=bounded(q.search.substring(4),80);
            if(q.exactJobId==null)throw new IllegalArgumentException("Exact Job ID is required after job:");
            q.search=null;
        }
        q.from=instant(values.get("from"));q.to=instant(values.get("to"));
        q.pinnedOnly="true".equals(choice(values.get("pinnedOnly"),"false","true","false"));
        q.grouped="target".equals(choice(values.get("groupBy"),null,"target"));return q;
    }
    String where(List<Object> parameters) {
        StringBuilder sql=new StringBuilder("(retention_state IS NULL OR retention_state<>'DELETING')");
        if("mine".equals(principalMode)){sql.append(" AND principal=?");parameters.add(actor);}
        else if("schedule".equals(principalMode))sql.append(" AND (principal LIKE 'load-schedule:%' OR principal LIKE 'debug-schedule:%' OR originating_schedule_id IS NOT NULL)");
        else if(principal!=null){sql.append(" AND LOCATE(LOWER(?),LOWER(principal))>0");parameters.add(principal);}
        if(submissionSource!=null){sql.append(" AND submission_source=?");parameters.add(submissionSource);}
        identityPredicate(sql,parameters,"execution_principal",executionPrincipalMode,executionPrincipal,executionPrincipalExact,actor);
        identityPredicate(sql,parameters,"schedule_owner",scheduleOwnerMode,scheduleOwner,scheduleOwnerExact,actor);
        identityPredicate(sql,parameters,"triggered_by",triggeredByMode,triggeredBy,triggeredByExact,actor);
        if(packageId!=null){sql.append(" AND package_id=?");parameters.add(packageId);}
        if(type!=null){sql.append(" AND ").append("run".equals(type)?"command IN ('run','debug')":"command=?");if(!"run".equals(type))parameters.add(type);}
        if(status!=null){sql.append(" AND status=?");parameters.add(status);}
        if(from!=null){sql.append(" AND CAST(created_at AS TIMESTAMP WITH TIME ZONE)>=CAST(? AS TIMESTAMP WITH TIME ZONE)");parameters.add(from);}
        if(to!=null){sql.append(" AND CAST(created_at AS TIMESTAMP WITH TIME ZONE)<=CAST(? AS TIMESTAMP WITH TIME ZONE)");parameters.add(to);}
        if(exactJobId!=null){sql.append(" AND job_id=?");parameters.add(exactJobId);}
        if(search!=null){sql.append(" AND (LOCATE(LOWER(?),LOWER(target_sort))>0 OR LOCATE(LOWER(?),LOWER(job_id))>0 OR EXISTS (SELECT 1 FROM job_targets jt WHERE jt.job_id=jobs.job_id AND LOCATE(?,jt.target_id)>0))");parameters.add(search);parameters.add(search);parameters.add(search.toLowerCase(java.util.Locale.ROOT));}
        if(pinnedOnly)sql.append(" AND pinned=TRUE");
        if("pending".equals(deletion))sql.append(" AND retention_state='DELETE_REQUESTED'");
        if("unmarked".equals(deletion))sql.append(" AND (retention_state IS NULL OR retention_state='')");
        return sql.toString();
    }
    private static void identityPredicate(StringBuilder sql,List<Object> parameters,String column,String mode,String value,boolean exact,String actor) {
        if("mine".equals(mode)){sql.append(" AND LOWER(").append(column).append(")=LOWER(?)");parameters.add(actor);}
        else if("text".equals(mode)&&value!=null) {
            if(exact){sql.append(" AND LOWER(").append(column).append(")=LOWER(?)");parameters.add(value);}
            else {sql.append(" AND LOCATE(LOWER(?),LOWER(").append(column).append("))>0");parameters.add(value);}
        }
    }
    String order() {
        if(grouped||sort.startsWith("target-"))return "target_sort "+(!grouped&&"target-desc".equals(sort)?"DESC":"ASC")+",package_id ASC,environment ASC,target_group ASC,created_at DESC,job_id DESC";
        return "created_at "+("oldest".equals(sort)?"ASC":"DESC")+",job_id "+("oldest".equals(sort)?"ASC":"DESC");
    }
    private static int integer(String value,int fallback,int min,int max){try{int n=value==null?fallback:Integer.parseInt(value);if(n<min||n>max)throw new IllegalArgumentException();return n;}catch(Exception invalid){throw new IllegalArgumentException("Invalid Jobs page range");}}
    private static String bounded(String value,int limit){if(value==null||value.isBlank())return null;if(value.length()>limit||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid Jobs filter");return value.strip();}
    private static String choice(String value,String fallback,String... options){if(value==null||value.isEmpty())return fallback;if(!List.of(options).contains(value))throw new IllegalArgumentException("Invalid Jobs filter choice");return value;}
    private static String instant(String value){if(value==null||value.isBlank())return null;try{return Instant.parse(value).toString();}catch(Exception invalid){throw new IllegalArgumentException("Invalid Jobs date");}}
    private static boolean bool(String value){if(value==null||value.isEmpty())return false;if("true".equals(value))return true;if("false".equals(value))return false;throw new IllegalArgumentException("Invalid Jobs filter");}
}
