package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

@Component
public class LdapAuthenticator {

    private final StudioProperties properties;

    public LdapAuthenticator(StudioProperties properties) {
        this.properties = properties;
    }

    public LdapUserProfile authenticate(String rawLogin, String password) {
        if (StringUtils.isBlank(password)) {
            throw new UnauthenticatedException("Email or password is incorrect");
        }
        StudioProperties.Ldap ldap = properties.getAuth().getLdap();
        String principal = LdapLoginNames.normalizePrincipal(rawLogin, ldap.normalizedDomain());
        if (ldap.hasManagerCredentials()) {
            return authenticateWithSearch(principal, password, ldap);
        }
        return authenticateDirectBind(principal, password, ldap);
    }

    private LdapUserProfile authenticateDirectBind(String principal, String password, StudioProperties.Ldap ldap) {
        LdapContext context = null;
        try {
            context = openContext(ldap.getUrl(), principal, password, ldap);
            LdapUserProfile profile = readSelfProfile(context, principal, ldap);
            enforceAllowedGroup(profile, ldap);
            return profile;
        } catch (UnauthenticatedException exception) {
            throw exception;
        } catch (NamingException exception) {
            throw new UnauthenticatedException("Email or password is incorrect");
        } finally {
            closeQuietly(context);
        }
    }

    private LdapUserProfile authenticateWithSearch(String principal, String password, StudioProperties.Ldap ldap) {
        LdapContext manager = null;
        LdapContext userContext = null;
        try {
            manager = openContext(ldap.getUrl(), ldap.getManagerDn(), ldap.getManagerPassword(), ldap);
            SearchResult hit = findUser(manager, principal, ldap);
            if (hit == null) {
                throw new UnauthenticatedException("Email or password is incorrect");
            }
            String userDn = hit.getNameInNamespace();
            userContext = openContext(ldap.getUrl(), userDn, password, ldap);
            LdapUserProfile profile = profileFromAttributes(principal, hit.getAttributes(), ldap);
            enforceAllowedGroup(profile, ldap);
            return profile;
        } catch (UnauthenticatedException exception) {
            throw exception;
        } catch (NamingException exception) {
            throw new UnauthenticatedException("Email or password is incorrect");
        } finally {
            closeQuietly(userContext);
            closeQuietly(manager);
        }
    }

    private SearchResult findUser(LdapContext context, String principal, StudioProperties.Ldap ldap)
            throws NamingException {
        String base = StringUtils.defaultIfBlank(ldap.getUserSearchBase(), ldap.resolvedBaseDn());
        String filter = resolvedSearchFilter(principal, ldap);
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(returningAttributes(ldap));
        NamingEnumeration<SearchResult> results = context.search(base, filter, controls);
        try {
            if (!results.hasMore()) {
                return null;
            }
            return results.next();
        } finally {
            results.close();
        }
    }

    private LdapUserProfile readSelfProfile(LdapContext context, String principal, StudioProperties.Ldap ldap)
            throws NamingException {
        String base = ldap.resolvedBaseDn();
        String filter = resolvedSearchFilter(principal, ldap);
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(returningAttributes(ldap));
        NamingEnumeration<SearchResult> results = context.search(base, filter, controls);
        try {
            if (results.hasMore()) {
                SearchResult hit = results.next();
                return profileFromAttributes(principal, hit.getAttributes(), ldap);
            }
        } catch (NamingException ignored) {
            // Directory may deny search on the user's own connection.
        } finally {
            results.close();
        }
        int at = principal.indexOf('@');
        String displayName = at > 0 ? principal.substring(0, at) : principal;
        return new LdapUserProfile(principal, displayName, null, List.of());
    }

    private LdapUserProfile profileFromAttributes(String principal, Attributes attributes, StudioProperties.Ldap ldap)
            throws NamingException {
        String mail = readAttribute(attributes, ldap.getEmailAttribute());
        String displayName = readAttribute(attributes, ldap.getDisplayNameAttribute());
        if (StringUtils.isBlank(displayName)) {
            displayName = readAttribute(attributes, "cn");
        }
        if (StringUtils.isBlank(displayName)) {
            int at = principal.indexOf('@');
            displayName = at > 0 ? principal.substring(0, at) : principal;
        }
        List<String> groups = readMultiAttribute(attributes, "memberOf");
        return new LdapUserProfile(principal, displayName.trim(), mail, groups);
    }

    private void enforceAllowedGroup(LdapUserProfile profile, StudioProperties.Ldap ldap) {
        String allowed = StringUtils.trimToNull(ldap.getAllowedGroup());
        if (allowed == null) {
            return;
        }
        if (!LdapGroupMatcher.matchesAny(profile.groupDns(), allowed)) {
            throw new UnauthenticatedException("Email or password is incorrect");
        }
    }

    private static String[] returningAttributes(StudioProperties.Ldap ldap) {
        return new String[] {
                ldap.getEmailAttribute(),
                ldap.getDisplayNameAttribute(),
                "cn",
                "memberOf"
        };
    }

    private String resolvedSearchFilter(String principal, StudioProperties.Ldap ldap) {
        String template = StringUtils.defaultIfBlank(
                ldap.getUserSearchFilter(),
                "(|(userPrincipalName={0})(mail={0}))");
        return template.replace("{0}", LdapEncoder.filterEncode(principal));
    }

    private LdapContext openContext(String url, String principal, String password, StudioProperties.Ldap ldap)
            throws NamingException {
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(ldap.resolvedConnectTimeoutMs()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(ldap.resolvedReadTimeoutMs()));
        return new InitialLdapContext(env, null);
    }

    private static String readAttribute(Attributes attributes, String name) throws NamingException {
        if (attributes == null || name == null) {
            return null;
        }
        Attribute attribute = attributes.get(name);
        if (attribute == null) {
            return null;
        }
        Object value = attribute.get();
        return value == null ? null : value.toString();
    }

    private static void closeQuietly(LdapContext context) {
        if (context == null) {
            return;
        }
        try {
            context.close();
        } catch (NamingException ignored) {
            // Best effort.
        }
    }

    private static List<String> readMultiAttribute(Attributes attributes, String name) throws NamingException {
        List<String> values = new ArrayList<>();
        if (attributes == null || name == null) {
            return values;
        }
        Attribute attribute = attributes.get(name);
        if (attribute == null) {
            return values;
        }
        NamingEnumeration<?> all = attribute.getAll();
        while (all.hasMore()) {
            Object value = all.next();
            if (value != null) {
                values.add(value.toString());
            }
        }
        return values;
    }
}
