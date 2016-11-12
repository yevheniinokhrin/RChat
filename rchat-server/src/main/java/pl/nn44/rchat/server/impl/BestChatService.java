package pl.nn44.rchat.server.impl;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Striped;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import pl.nn44.rchat.protocol.*;
import pl.nn44.rchat.protocol.ChatException.Reason;
import pl.nn44.rchat.protocol.WhatsUp.What;
import pl.nn44.rchat.server.as.AsLoggable;
import pl.nn44.rchat.server.model.SeChannel;
import pl.nn44.rchat.server.model.SeUser;
import pl.nn44.rchat.server.util.BigIdGenerator;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@AsLoggable
public class BestChatService implements ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(BestChatService.class);

    public static int MAX_NEWS_PER_REQUEST = 8;
    public static int ID_RANDOM_BITS = 8 * BigIdGenerator.BITS_PER_CHAR;
    public static int STRIPED_LOCKS = 32;

    private final Random random = new SecureRandom();
    private final Iterator<String> idGenerator = BigIdGenerator.bits(random, ID_RANDOM_BITS);
    private final Pattern nameValidator = Pattern.compile("[a-zA-Z0-9_.-]{1,10}");

    private final ConcurrentMap<String, String> accounts/*username/password*/ = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SeUser> sessionToUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SeChannel> channelByName = new ConcurrentHashMap<>();

    private final Striped<Lock> stripedLocks = Striped.lazyWeakLock(STRIPED_LOCKS);

    public BestChatService() {
        accounts.put("admin", "admin");
        accounts.put("student", "student");

        channelByName.put("anybody", new SeChannel("anybody", null, ""));
        channelByName.put("python", new SeChannel("python", null, "python lovers"));
        channelByName.put("cars", new SeChannel("cars", null, "no bike"));
        channelByName.put("students", new SeChannel("students", null, "trust me, i'm an engineer"));
        channelByName.put("admins", new SeChannel("admins", "admins", "keep silence"));

        channelByName.get("anybody").getAdmins().add("admin");
        channelByName.get("python").getAdmins().add("admin");
        channelByName.get("cars").getAdmins().add("admin");
        channelByName.get("students").getAdmins().add("admin");
        channelByName.get("students").getAdmins().add("student");
        channelByName.get("admins").getAdmins().add("admin");

        channelByName.get("python").getBanned().add("java");

        LOG.info("{} instance created.", getClass().getSimpleName());
    }

    @Override
    public Response<String> login(String username, @Nullable String password) throws ChatException {
        Locks locks = locks(null, null, username);

        try {
            if (sessionToUser.containsValue(SeUser.Dummy(username))) {
                throw new ChatException(Reason.ALREADY_LOGGED_IN);
            }

            if (!Objects.equals(accounts.get(username), password)) {
                throw new ChatException(Reason.GIVEN_BAD_PASSWORD);
            }

            if (!nameValidator.matcher(username).matches()) {
                throw new ChatException(Reason.GIVEN_BAD_USERNAME);
            }

            String session = idGenerator.next();
            SeUser exUser = new SeUser(session, username);
            sessionToUser.put(session, exUser);
            return Response.Ok(session);

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> logout(String session) throws ChatException {
        Locks locks = locks(session, null, null);

        try {
            Params params = params(session, null, null, false);

            for (SeChannel channel : params.caller.getChannels()) {
                // double lock is safe operation:
                // "If the current thread already holds the lock
                // then the hold count is incremented by one and the method returns immediately."
                part(session, channel.getName());
            }

            sessionToUser.remove(session);

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<RcChannel[]> channels(String session) throws ChatException {
        Locks locks = locks(session, null, null);

        try {
            //  side-effect used: verify session
            params(session, null, null, false);

            RcChannel[] rcChannels = channelByName.values().stream()
                    .map(c -> new RcChannel(
                            c.getName(),
                            c.getPassword() != null
                    ))
                    .toArray(RcChannel[]::new);

            return Response.Ok(rcChannels);

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<RcChannel> join(String session, String channel, @Nullable String password) throws ChatException {
        Locks locks = locks(session, channel, null);

        try {
            Params params = params(session, null, null, false);
            // cannot get channel in one params request as then there is verified if user is on channel
            params.channel = params(null, channel, null, false).channel;

            if (!Objects.equals(params.channel.getPassword(), password)) {
                throw new ChatException(Reason.GIVEN_BAD_PASSWORD);
            }

            if (params.channel.getBanned().contains(params.caller.getUsername())) {
                throw new ChatException(Reason.UNWELCOME_BANNED);
            }

            /* unnecessary until own channels are allowed
            if (!nameValidator.matcher(channel).matches()) {
                throw new ChatException(Reason.GIVEN_BAD_CHANNEL);
            }
            */

            boolean addC = params.channel.getUsers().addIfAbsent(params.caller);
            boolean addU = params.caller.getChannels().addIfAbsent(params.channel);
            boolean admin = params.channel.getAdmins().contains(params.caller.getUsername());

            if (addC ^ addU) {
                LOG.warn("join(): addC ^ addU is true, but it should not");
            }

            if (addC) {
                WhatsUp whatsUp = new WhatsUp(
                        What.JOIN,
                        params.channel.getName(),
                        params.caller.getUsername()
                );

                params.channel.getUsers().stream()
                        .filter(cu -> !cu.equals(params.caller))
                        .forEach(cu -> cu.getNews().offer(whatsUp));
            }

            if (addC && admin) {
                WhatsUp whatsUp = new WhatsUp(
                        What.ADMIN,
                        params.channel.getName(),
                        params.caller.getUsername(),
                        null,
                        "ON"
                );

                params.channel.getUsers().stream()
                        .filter(cu -> !cu.equals(params.caller))
                        .forEach(cu -> cu.getNews().offer(whatsUp));

            }

            RcChUser[] rcChUsers = params.channel.getUsers()
                    .stream()
                    .map(cUser -> new RcChUser(
                            params.channel.getName(),
                            cUser.getUsername(),
                            accounts.containsKey(cUser.getUsername()),
                            params.caller.getIgnored().contains(cUser),
                            params.channel.getAdmins().contains(cUser.getUsername()),
                            params.channel.getBanned().contains(cUser.getUsername())
                    ))
                    .toArray(RcChUser[]::new);

            RcChannel rcChannel = new RcChannel(
                    params.channel.getName(),
                    params.channel.getPassword() != null,
                    params.channel.getTopic(),
                    rcChUsers
            );

            return Response.Ok(rcChannel);

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> part(String session, String channel) throws ChatException {
        Locks locks = locks(session, channel, null);

        try {
            Params params = params(session, channel, null, false);

            boolean removeC = params.channel.getUsers().remove(params.caller);
            boolean removeU = params.caller.getChannels().remove(params.channel);

            if (removeC ^ removeU) {
                LOG.warn("part(): removeC ^ removeU is true, but it should not");
            }

            if (removeC) {
                WhatsUp whatsUp = new WhatsUp(
                        What.PART,
                        params.channel.getName(),
                        params.caller.getUsername()
                );

                for (SeUser cu : params.channel.getUsers()) {
                    cu.getNews().offer(whatsUp);
                }
            }

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> topic(String session, String channel, String text) throws ChatException {
        Locks locks = new Locks(session, channel, null);

        try {
            Params params = params(session, channel, null, true);

            boolean change = !params.channel.getTopic().equals(text);

            if (change) {
                params.channel.setTopic(text);

                WhatsUp whatsUp = new WhatsUp(
                        What.TOPIC,
                        params.channel.getName(),
                        params.caller.getUsername(),
                        text
                );

                for (SeUser cu : params.channel.getUsers()) {
                    cu.getNews().offer(whatsUp);
                }
            }

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> kick(String session, String channel, String username) throws ChatException {
        Locks locks = locks(session, channel, username);

        try {
            Params params = params(session, channel, username, true);

            boolean removeC = params.channel.getUsers().remove(params.affUser);
            boolean removeU = params.affUser.getChannels().remove(params.channel);

            if (removeC ^ removeU) {
                LOG.warn("kick(): removeC ^ removeU is true, but it should not");
            }

            if (removeC) {
                WhatsUp whatsUp = new WhatsUp(
                        What.KICK,
                        params.channel.getName(),
                        params.affUser.getUsername(),
                        params.caller.getUsername()
                );

                for (SeUser cu : params.channel.getUsers()) {
                    cu.getNews().offer(whatsUp);
                }
            }

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> ban(String session, String channel, String username, boolean state) throws ChatException {
        Locks locks = locks(session, channel, username);

        try {
            Params params = params(session, channel, username, true);

            boolean change = state
                    ? params.channel.getBanned().addIfAbsent(params.affUser.getUsername())
                    : params.channel.getBanned().remove(params.affUser.getUsername());

            if (change) {
                WhatsUp whatsUp = new WhatsUp(
                        What.BAN,
                        params.channel.getName(),
                        params.affUser.getUsername(),
                        params.caller.getUsername(),
                        state ? "ON" : "OFF"
                );

                for (SeUser cu : params.channel.getUsers()) {
                    cu.getNews().offer(whatsUp);
                }
            }

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> admin(String session, String channel, String username, boolean state) throws ChatException {
        Locks locks = locks(session, channel, username);

        try {
            Params params = params(session, channel, username, true);

            boolean change = state
                    ? params.channel.getAdmins().addIfAbsent(params.affUser.getUsername())
                    : params.channel.getAdmins().remove(params.affUser.getUsername());

            if (change) {
                WhatsUp whatsUp = new WhatsUp(
                        What.ADMIN,
                        params.channel.getName(),
                        params.affUser.getUsername(),
                        params.caller.getUsername(),
                        state ? "ON" : "OFF"
                );

                for (SeUser cu : params.channel.getUsers()) {
                    cu.getNews().offer(whatsUp);
                }
            }

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> ignore(String session, String username, boolean state) throws ChatException {
        Locks locks = locks(session, null, username);

        try {
            Params params = params(session, null, username, false);

            boolean change = state
                    ? params.caller.getIgnored().addIfAbsent(params.affUser)
                    : params.caller.getIgnored().remove(params.affUser);

            if (change) {
                WhatsUp whatsUp = new WhatsUp(
                        What.IGNORE,
                        null,
                        params.affUser.getUsername(),
                        params.caller.getUsername(),
                        state ? "ON" : "OFF"
                );

                params.caller.getNews().offer(whatsUp);
                params.affUser.getNews().offer(whatsUp);
            }

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> privy(String session, String username, String text) throws ChatException {
        Locks locks = locks(session, null, username);

        try {
            Params params = params(session, null, username, false);

            boolean ignore = params.affUser.getIgnored().contains(params.caller);

            if (!ignore) {
                WhatsUp whatsUp = new WhatsUp(
                        What.PRIVY,
                        null,
                        params.affUser.getUsername(),
                        params.caller.getUsername(),
                        text
                );

                params.caller.getNews().offer(whatsUp);
                params.affUser.getNews().offer(whatsUp);
            }

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<?> message(String session, String channel, String text) throws ChatException {
        Locks locks = locks(session, channel, null);

        try {
            Params params = params(session, channel, null, false);

            WhatsUp whatsUp = new WhatsUp(
                    What.MESSAGE,
                    params.channel.getName(),
                    params.caller.getUsername(),
                    text
            );

            for (SeUser cu : params.channel.getUsers()) {
                boolean ignore = cu.getIgnored().contains(params.caller);

                if (!ignore) {
                    cu.getNews().offer(whatsUp);
                }
            }

            return Response.Ok();

        } finally {
            locks.unlock();
        }
    }

    @Override
    public Response<WhatsUp[]> whatsUp(String session, int longPoolingTimeoutMs) throws ChatException {
        Params params = params(session, null, null, false);

        List<WhatsUp> news = new LinkedList<>();

        while (news.size() < MAX_NEWS_PER_REQUEST) {
            WhatsUp poll = params.caller.getNews().poll();

            if (poll != null) {
                news.add(poll);

            } else if (news.size() == 0) {
                try {
                    poll = params.caller.getNews().poll(longPoolingTimeoutMs, TimeUnit.MILLISECONDS);

                    if (poll != null) {
                        news.add(poll);
                    } else {
                        break;
                    }

                } catch (InterruptedException e) {
                    LOG.warn("whatsUp assertion error", e);
                    break;
                }

            } else {
                break;
            }
        }

        WhatsUp[] newsArray = news.toArray(new WhatsUp[news.size()]);
        return Response.Ok(newsArray);
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Scheduled(cron = "0 */5 * * * *")
    public int sessionCleanup() {
        LocalDateTime now = LocalDateTime.now();

        List<Map.Entry<String, SeUser>> ghosts =
                sessionToUser
                        .entrySet().stream()
                        .filter(se -> ChronoUnit.MINUTES.between(se.getValue().getLastSync(), now) >= 3)
                        .collect(Collectors.toList());

        ghosts.forEach(se -> {
            try {
                logout(se.getKey());

            } catch (ChatException e) {
                LOG.warn("sessionCleanup assertion error", e);
                throw new AssertionError(e);
            }
        });

        return ghosts.size();
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public Response<?> test(boolean exception) throws ChatException {
        if (exception) {
            throw new ChatException(Reason.NO_PERMISSION);
        }

        return Response.Ok(
                Response.Ok(
                        new ImmutableMap.Builder<>()
                                .put("null", "null")
                                .put("string", "xxx")
                                .put("number", 999)
                                .put("response", Response.Ok(10))
                                .put("response-null", Response.Ok())
                                .put("array-object", new Object[]{1, "2", What.BAN})
                                .put("list", Arrays.asList(2, "3", What.JOIN))
                                .put("map", ImmutableMap.<Object, Object>of("key", "value"))
                                .put("whatsUp", new WhatsUp(What.TOPIC, "any", "topic"))
                                .put("whatsUp-param", new WhatsUp(What.TOPIC, "any", "topic", "p1", "p2"))
                                .build()
                )
        );
    }

    // ---------------------------------------------------------------------------------------------------------------

    private class Params {

        public SeUser caller;
        public SeChannel channel;
        public SeUser affUser;

        Params(String session,
               String channel,
               String username,
               boolean needAdmin)
                throws ChatException {

            if (session != null) {
                this.caller = sessionToUser.get(session);
                if (this.caller == null) {
                    throw new ChatException(Reason.GIVEN_BAD_SESSION);
                }
            }

            if (channel != null) {
                this.channel = channelByName.get(channel);
                if (this.channel == null) {
                    throw new ChatException(Reason.GIVEN_BAD_CHANNEL);
                }
            }

            if (this.caller != null && this.channel != null) {
                if (!this.channel.getUsers().contains(this.caller)) {
                    throw new ChatException(Reason.NO_PERMISSION);
                }
            }

            if (username != null && this.channel != null) {
                SeUser dummyAffUser = SeUser.Dummy(username);

                this.affUser = this.channel.getUsers().stream()
                        .filter(u -> u.equals(dummyAffUser))
                        .findFirst().orElse(null);
                if (this.affUser == null) {
                    throw new ChatException(Reason.GIVEN_BAD_USERNAME);
                }
            }

            if (needAdmin && this.channel != null && this.caller != null) {
                if (this.channel.getAdmins().contains(this.caller.getUsername())) {
                    throw new ChatException(Reason.NO_PERMISSION);
                }
            }

            if (this.caller != null) {
                this.caller.updateLastSync();
            }
        }
    }

    // checks if:
    // - caller(session) is proper (GIVEN_BAD_SESSION)
    // - channel(channel) is proper (GIVEN_BAD_CHANNEL)
    // - affUser(username) is on channel (GIVEN_BAD_USERNAME)
    // - caller is on channel (NO_PERMISSION)
    // - caller is admin on channel (NO_PERMISSION)
    // and:
    // - update caller last sync timestamp
    private Params params(String session,
                          String channel,
                          String username,
                          boolean needAdmin)
            throws ChatException {

        return new Params(session, channel, username, needAdmin);
    }

    // ---------------------------------------------------------------------------------------------------------------

    private class Locks {

        Lock lock$caller;
        Lock lock$channel;
        Lock lock$affUser;

        Locks(String session,
              String channel,
              String username)
                throws ChatException {

            if (session != null) {
                SeUser user = sessionToUser.get(session);

                if (user == null) {
                    throw new ChatException(Reason.GIVEN_BAD_SESSION);
                }

                this.lock$caller = stripedLocks.get("U$" + user.getUsername());
            }
            if (username != null) {
                this.lock$affUser = stripedLocks.get("U$" + username);
            }
            if (channel != null) {
                this.lock$channel = stripedLocks.get("C$" + channel);
            }
        }

        void lock() {
            if (this.lock$channel != null) {
                this.lock$channel.lock();
            }
            if (this.lock$affUser != null) {
                this.lock$affUser.lock();
            }
            if (this.lock$caller != null) {
                this.lock$caller.lock();
            }
        }

        void unlock() {
            if (this.lock$channel != null) {
                this.lock$channel.unlock();
            }
            if (this.lock$affUser != null) {
                this.lock$affUser.unlock();
            }
            if (this.lock$caller != null) {
                this.lock$caller.unlock();
            }
        }
    }

    private Locks locks(String session,
                        String channel,
                        String username)
            throws ChatException {

        Locks locks = new Locks(session, channel, username);
        locks.lock();
        return locks;
    }
}
