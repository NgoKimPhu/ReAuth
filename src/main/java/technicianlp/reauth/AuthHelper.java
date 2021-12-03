package technicianlp.reauth;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import com.google.common.base.Preconditions;
import com.mojang.authlib.Agent;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.YggdrasilUserAuthentication;
import com.mojang.util.UUIDTypeAdapter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Session;
import net.minecraft.client.util.Session.AccountType;
import technicianlp.reauth.mixin.MinecraftClientMixin;

public final class AuthHelper {

    /**
     * Time for which the Validity gets cached (5 Minutes)
     */
    private static final long cacheTime = 5 * 1000 * 60L;

    /**
     * Reflective YggdrasilUserAuthentication#setAccessToken
     */
    private static final Field accessToken;
    /**
     * Reflective YggdrasilUserAuthentication#checkTokenValidity
     */
    private static final Method checkTokenValidity;
    /**
     * Reflective YggdrasilUserAuthentication#logInWithPassword
     */
    private static final Method logInWithPassword;
    /**
     * Reflective {@link YggdrasilUserAuthentication#YggdrasilUserAuthentication}
     */
    private static final Constructor<YggdrasilUserAuthentication> authConstructor;
    private static final BiFunction<YggdrasilAuthenticationService, String, Object[]> authParameterFactory;

    static {
        Class<YggdrasilUserAuthentication> clz = YggdrasilUserAuthentication.class;
        accessToken = ReflectionHelper.findField(clz, "accessToken");
        Preconditions.checkNotNull(accessToken, "Reflection failed: accessToken");
        checkTokenValidity = ReflectionHelper.findMethod(clz, "checkTokenValidity");
        Preconditions.checkNotNull(checkTokenValidity, "Reflection failed: checkTokenValidity");
        logInWithPassword = ReflectionHelper.findMethod(clz, "logInWithPassword");
        Preconditions.checkNotNull(logInWithPassword, "Reflection failed: logInWithPassword");

        // Constructor changed between 1.16.3 and 1.16.4: clientToken needs to be passed into the YUA
        Constructor<YggdrasilUserAuthentication> constructor;
        constructor = ReflectionHelper.findConstructor(clz, YggdrasilAuthenticationService.class, String.class, Agent.class);
        if (constructor != null) {
            authParameterFactory = (authService, clientToken) -> new Object[]{authService, clientToken, Agent.MINECRAFT};
        } else {
            constructor = ReflectionHelper.findConstructor(clz, YggdrasilAuthenticationService.class, Agent.class);
            authParameterFactory = (authService, clientToken) -> new Object[]{authService, Agent.MINECRAFT};
        }
        Preconditions.checkNotNull(constructor, "Reflection failed: <init>");
        authConstructor = constructor;
    }

    /**
     * Current cached Session Validity
     */
    private SessionStatus status = SessionStatus.UNKNOWN;
    private long lastCheck = 0;

    /**
     * Two Authentication Service are required as a
     * Validation-Request may not have a clientToken,
     * yet a Login-Request requires it.
     */
    private final YggdrasilUserAuthentication checkAuth;
    private final YggdrasilUserAuthentication loginAuth;

    /**
     * Pattern for valid Minecraft Names according to Wiki
     */
    private final Pattern namePattern = Pattern.compile("[A-Za-z0-9_]{2,16}");

    public AuthHelper() {
        Proxy proxy = MinecraftClient.getInstance().getNetworkProxy();
        String clientId = UUID.randomUUID().toString();
        Object[] checkAuthParams = authParameterFactory.apply(new YggdrasilAuthenticationService(proxy, (String) null), null);
        checkAuth = ReflectionHelper.callConstructor(authConstructor, checkAuthParams);
        Object[] loginAuthParams = authParameterFactory.apply(new YggdrasilAuthenticationService(proxy, clientId), clientId);
        loginAuth = ReflectionHelper.callConstructor(authConstructor, loginAuthParams);
    }

    /**
     * Get the cached Validity Status of the accessToken
     * Re-Validation is done if the cache expires or it is forced by the Parameter of the same name
     */
    public SessionStatus getSessionStatus(boolean force) {
        if (force || lastCheck + cacheTime < System.currentTimeMillis())
            status = SessionStatus.UNKNOWN;

        if (status == SessionStatus.UNKNOWN) {
            status = SessionStatus.REFRESHING;
            lastCheck = System.currentTimeMillis();

            Thread t = new Thread(this::updateSessionStatus, "ReAuth Session Validator");
            t.setDaemon(true);
            t.start();
        }
        return status;
    }

    /**
     * Uses the Validate Endpoint to check the current Tokens validity and updates the cache accordingly
     */
    private void updateSessionStatus() {
        ReflectionHelper.setField(accessToken, checkAuth, getSession().getAccessToken());
        boolean valid = ReflectionHelper.callMethod(checkTokenValidity, checkAuth);
        status = valid ? SessionStatus.VALID : SessionStatus.INVALID;
        lastCheck = System.currentTimeMillis();
    }

    /**
     * Login with the Supplied Username and Password
     * Password is saved to config if {@code savePassword} is true
     **/
    public void login(String user, char[] password, boolean savePassword) throws AuthenticationException {
        login(user, new String(password), savePassword);
    }

    public void login(String user, String password, boolean savePassword) throws AuthenticationException {
        loginAuth.setUsername(user);
        loginAuth.setPassword(password);
        try {
            try {
                ReflectionHelper.callMethod(logInWithPassword, loginAuth);
            } catch (ReflectionHelper.UncheckedInvocationTargetException exception) {
                Throwable parent = exception.getCause();
                if (parent instanceof AuthenticationException)
                    throw (AuthenticationException) parent;
                ReAuth.log.error("LogInWithPassword has thrown unexpected exception:", parent);
            }

            String username = loginAuth.getSelectedProfile().getName();
            String uuid = UUIDTypeAdapter.fromUUID(loginAuth.getSelectedProfile().getId());
            String access = loginAuth.getAuthenticatedToken();
            Optional<String> clientId = getSession().getClientId();
            AccountType type = AccountType.byName(loginAuth.getUserType().getName());

            Session session = new Session(username, uuid, access, null, clientId, type);
//            session.setProperties(loginAuth.getUserProperties());

            loginAuth.logOut();

            setSession(session);

            ReAuth.config.setCredentials(username, user, savePassword ? password : "");
        } finally {
            loginAuth.logOut();
        }
    }

    /**
     * Sets the Players Offline Username
     * UUID is generated from the supplied Username
     */
    public void offline(String username) {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        setSession(new Session(username, uuid.toString(), "invalid", null, getSession().getClientId(), AccountType.byName("legacy")));
        ReAuth.log.info("Offline Username set!");
        ReAuth.config.setCredentials(username, "", "");
    }

    private Session getSession() {
        return MinecraftClient.getInstance().getSession();
    }

    private void setSession(Session s) {
        ((MinecraftClientMixin) MinecraftClient.getInstance()).reauthSetSession(s);
        status = SessionStatus.UNKNOWN;
    }

    public boolean isValidName(String username) {
        return namePattern.matcher(username).matches();
    }

    public enum SessionStatus {
        VALID("valid"),
        UNKNOWN("unknown"),
        REFRESHING("unknown"),
        INVALID("invalid");

        private final String translationKey;

        SessionStatus(String translationKey) {
            this.translationKey = "reauth.status." + translationKey;
        }

        public String getTranslationKey() {
            return translationKey;
        }
    }
}
