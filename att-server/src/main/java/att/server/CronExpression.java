package att.server;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Small, deliberately bounded five-field cron parser used by persisted Load schedules. */
final class CronExpression {
    private final Field minute, hour, dayOfMonth, month, dayOfWeek;

    private CronExpression(Field minute, Field hour, Field dayOfMonth, Field month, Field dayOfWeek) {
        this.minute=minute;this.hour=hour;this.dayOfMonth=dayOfMonth;this.month=month;this.dayOfWeek=dayOfWeek;
    }

    static CronExpression parse(String source, int minimumIntervalMinutes) {
        if(source==null||source.isBlank()||source.length()>120)throw new IllegalArgumentException("cron is required and must be at most 120 characters");
        String[] parts=source.trim().split("\\s+");
        if(parts.length!=5)throw new IllegalArgumentException("cron must contain five fields: minute hour day-of-month month day-of-week");
        CronExpression expression=new CronExpression(Field.parse(parts[0],0,59,"minute"),Field.parse(parts[1],0,23,"hour"),
                Field.parse(parts[2],1,31,"day-of-month"),Field.parse(parts[3],1,12,"month"),Field.parse(parts[4],0,7,"day-of-week").normalizeSunday());
        expression.validateMinimumInterval(minimumIntervalMinutes);
        return expression;
    }

    Instant nextAfter(Instant after, ZoneId zone) {
        LocalDate first=after.atZone(zone).toLocalDate().minusDays(1);
        ZoneRules rules=zone.getRules();
        for(int day=0;day<366*8;day++) {
            LocalDate date=first.plusDays(day);
            if(!matchesDate(date))continue;
            List<Instant> candidates=new ArrayList<>();
            for(int h:hour.values) for(int m:minute.values) {
                LocalDateTime local=date.atTime(h,m);
                List<ZoneOffset> offsets=rules.getValidOffsets(local);
                if(offsets.isEmpty())continue; // A spring-forward gap has no matching instant.
                for(ZoneOffset offset:offsets)candidates.add(local.toInstant(offset));
            }
            Collections.sort(candidates);
            for(Instant candidate:candidates)if(candidate.isAfter(after))return candidate;
        }
        throw new IllegalArgumentException("cron has no occurrence in the next eight years for timezone "+zone.getId());
    }

    private boolean matchesDate(LocalDate date) {
        if(!month.values.contains(date.getMonthValue()))return false;
        boolean dom=dayOfMonth.values.contains(date.getDayOfMonth());
        int dow=date.getDayOfWeek().getValue()%7;
        boolean weekday=dayOfWeek.values.contains(dow);
        if(dayOfMonth.wildcard&&dayOfWeek.wildcard)return true;
        if(dayOfMonth.wildcard)return weekday;
        if(dayOfWeek.wildcard)return dom;
        return dom||weekday;
    }

    private void validateMinimumInterval(int minimumMinutes) {
        if(minimumMinutes<1)throw new IllegalArgumentException("minimum schedule interval must be positive");
        List<Integer> minutesOfDay=new ArrayList<>();
        for(int h:hour.values)for(int m:minute.values)minutesOfDay.add(h*60+m);
        Collections.sort(minutesOfDay);
        if(minutesOfDay.size()<2)return;
        int shortest=24*60;
        for(int i=1;i<minutesOfDay.size();i++)shortest=Math.min(shortest,minutesOfDay.get(i)-minutesOfDay.get(i-1));
        shortest=Math.min(shortest,24*60-minutesOfDay.get(minutesOfDay.size()-1)+minutesOfDay.get(0));
        if(shortest<minimumMinutes)throw new IllegalArgumentException("cron occurrences must be at least "+minimumMinutes+" minutes apart");
    }

    void validateTimezoneMinimumInterval(ZoneId zone,int minimumMinutes,Instant from) {
        var rules=zone.getRules();Instant cursor=from;
        for(int i=0;i<24;i++) {
            var transition=rules.nextTransition(cursor);if(transition==null)break;
            if(transition.isOverlap()&&transition.getDuration().abs().toMinutes()<minimumMinutes) {
                LocalDateTime fromLocal=transition.getDateTimeAfter(),toLocal=transition.getDateTimeBefore();
                LocalDate date=fromLocal.toLocalDate();
                for(int h:hour.values)for(int m:minute.values) {
                    LocalDateTime candidate=date.atTime(h,m);
                    if(matchesDate(date)&&!candidate.isBefore(fromLocal)&&candidate.isBefore(toLocal))
                        throw new IllegalArgumentException("cron occurrences must be at least "+minimumMinutes+" minutes apart in timezone "+zone.getId());
                }
            }
            cursor=transition.getInstant().plusSeconds(1);
        }
    }

    private static final class Field {
        final Set<Integer> values;
        final boolean wildcard;
        private Field(Set<Integer> values,boolean wildcard){this.values=values;this.wildcard=wildcard;}
        Field normalizeSunday(){if(!values.contains(7))return this;Set<Integer> normalized=new TreeSet<>(values);normalized.remove(7);normalized.add(0);return new Field(Collections.unmodifiableSet(normalized),wildcard);}
        static Field parse(String value,int min,int max,String name) {
            Set<Integer> out=new TreeSet<>();boolean wildcard="*".equals(value);
            for(String item:value.split(",",-1)) {
                if(item.isEmpty())throw invalid(name);
                String[] stepParts=item.split("/",-1);
                if(stepParts.length>2)throw invalid(name);
                int step=stepParts.length==2?positive(stepParts[1],name):1;
                String range=stepParts[0];int start,end;
                if("*".equals(range)){start=min;end=max;}
                else if(range.contains("-")) {
                    String[] bounds=range.split("-",-1);if(bounds.length!=2)throw invalid(name);
                    start=number(bounds[0],name);end=number(bounds[1],name);if(start>end)throw invalid(name);
                } else {start=number(range,name);end=stepParts.length==2?max:start;}
                if(start<min||end>max)throw invalid(name);
                for(int n=start;n<=end;n+=step)out.add(n);
            }
            if(out.isEmpty())throw invalid(name);
            return new Field(Collections.unmodifiableSet(out),wildcard||out.size()==max-min+1);
        }
        private static int positive(String value,String name){int n=number(value,name);if(n<1)throw invalid(name);return n;}
        private static int number(String value,String name){try{return Integer.parseInt(value);}catch(NumberFormatException invalid){throw invalid(name);}}
        private static IllegalArgumentException invalid(String name){return new IllegalArgumentException("cron has an invalid "+name+" field");}
    }
}
