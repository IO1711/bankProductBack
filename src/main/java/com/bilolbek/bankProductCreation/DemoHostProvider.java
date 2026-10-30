package com.bilolbek.bankProductCreation;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Local stand-in for host SSO and organization APIs. */
@Component
@ConditionalOnProperty(name="app.host.mode",havingValue="demo",matchIfMissing=true)
public final class DemoHostProvider implements HostTicketValidator,OrganizationDirectory {
    private record IssuedTicket(String userId,Instant expires) { }
    private final Map<String,IssuedTicket> tickets=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    private final Clock clock;

    public DemoHostProvider() { this(Clock.systemUTC()); }
    DemoHostProvider(Clock clock) { this.clock=clock; }

    private static BankUser demoUser(String id,String name,String position,String department,boolean head,boolean chairman) {
        return new BankUser(id,name,String.format(Locale.ROOT,"%014d",Integer.toUnsignedLong(id.hashCode())),
            position,department,head,chairman,"uz");
    }

    private static final List<BankDepartment> DEPARTMENTS=List.of(
        new BankDepartment("D-01","Boshqaruv",""),
        new BankDepartment("D-07","Kredit bo‘limi","u-head"),
        new BankDepartment("D-12","Risk bo‘limi","u-risk-head"),
        new BankDepartment("D-15","Yuridik bo‘lim","u-legal-head"),
        new BankDepartment("D-20","Operatsiyalar bo‘limi","u-exec-head")
    );
    private static final List<BankUser> USERS=List.of(
        demoUser("u-creator","Aliyev Vali","Mutaxassis","D-07",false,false),
        demoUser("u-head","Karimova Dilnoza","Bo‘lim boshlig‘i","D-07",true,false),
        demoUser("u-notice","Rasulov Akmal","Yetakchi mutaxassis","D-07",false,false),
        demoUser("u-risk-head","Sobirov Javlon","Bo‘lim boshlig‘i","D-12",true,false),
        demoUser("u-risk","Nazarova Mohira","Taqrizchi","D-12",false,false),
        demoUser("u-legal-head","Yusupova Sevara","Bo‘lim boshlig‘i","D-15",true,false),
        demoUser("u-legal","Olimov Aziz","Taqrizchi","D-15",false,false),
        demoUser("u-chair","Tursunov Anvar","Boshqaruv raisi","D-01",false,true),
        demoUser("u-exec-head","Ismoilov Sardor","Bo‘lim boshlig‘i","D-20",true,false),
        demoUser("u-exec","Qodirova Malika","Ijrochi bo‘lim xodimi","D-20",false,false)
    );

    public String issueTicket(String userId) {
        if(user(userId)==null) throw WorkflowService.error(HttpStatus.NOT_FOUND,"Demo user not found");
        Instant now=clock.instant();
        tickets.entrySet().removeIf(entry->!now.isBefore(entry.getValue().expires()));
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String ticket=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket,new IssuedTicket(userId,now.plusSeconds(60)));
        return ticket;
    }

    @Override public BankUser validateAndConsume(String ticket) {
        IssuedTicket issued=ticket==null?null:tickets.remove(ticket);
        if(issued==null || !clock.instant().isBefore(issued.expires()))
            throw WorkflowService.error(HttpStatus.UNAUTHORIZED,"Invalid, expired, or already used SSO ticket");
        BankUser user=user(issued.userId());
        if(user==null) throw WorkflowService.error(HttpStatus.UNAUTHORIZED,"User no longer exists in the host directory");
        return user;
    }

    @Override public List<BankDepartment> departments() { return DEPARTMENTS; }
    @Override public BankDepartment department(String id) {
        return DEPARTMENTS.stream().filter(department->department.id().equals(id)).findFirst().orElse(null);
    }
    @Override public List<BankUser> employees(String departmentId) {
        return USERS.stream().filter(user->user.department_id.equals(departmentId)).toList();
    }
    @Override public BankUser user(String id) {
        return USERS.stream().filter(user->user.user_id.equals(id)).findFirst().orElse(null);
    }
    public List<BankUser> demoUsers() { return USERS; }
}
