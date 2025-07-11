/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.services.resources.admin;

import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.extensions.Extension;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.Profile;
import org.keycloak.common.util.ObjectUtil;
import org.keycloak.common.util.Time;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.*;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.models.utils.RepresentationToModel;
import org.keycloak.models.utils.StripSecretsUtils;
import org.keycloak.policy.PasswordPolicyNotMetException;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.protocol.oidc.utils.RedirectUtils;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.ForbiddenException;
import org.keycloak.services.resources.KeycloakOpenAPI;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;
import org.keycloak.services.resources.admin.permissions.UserPermissionEvaluator;
import org.keycloak.userprofile.UserProfile;
import org.keycloak.userprofile.UserProfileProvider;
import org.keycloak.utils.SearchQueryUtils;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.keycloak.models.utils.KeycloakModelUtils.findGroupByPath;
import static org.keycloak.userprofile.UserProfileContext.USER_API;

import org.keycloak.authentication.actiontoken.execactions.ExecuteActionsActionToken;
import org.keycloak.services.resources.LoginActionsService;

/**
 * Base resource for managing users
 *
 * @resource Users
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Extension(name = KeycloakOpenAPI.Profiles.ADMIN, value = "")
public class UsersResource {

    private static final Logger logger = Logger.getLogger(UsersResource.class);
    private static final String SEARCH_ID_PARAMETER = "id:";

    protected final RealmModel realm;

    private final AdminPermissionEvaluator auth;

    private final AdminEventBuilder adminEvent;

    protected final ClientConnection clientConnection;

    protected final KeycloakSession session;

    protected final HttpHeaders headers;

    public UsersResource(KeycloakSession session, AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
        this.session = session;
        this.clientConnection = session.getContext().getConnection();
        this.auth = auth;
        this.realm = session.getContext().getRealm();
        this.adminEvent = adminEvent.resource(ResourceType.USER);
        this.headers = session.getContext().getRequestHeaders();
    }

    /**
     * Create a new user
     *
     * Username must be unique.
     *
     * @param rep
     * @return
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Tag(name = KeycloakOpenAPI.Admin.Tags.USERS)
    @Operation( summary = "Create a new user Username must be unique.")
    public Response createUser(
            @QueryParam(OIDCLoginProtocol.REDIRECT_URI_PARAM) String redirectUri,
            @QueryParam(OIDCLoginProtocol.CLIENT_ID_PARAM) String clientId,
            @QueryParam("lifespan") Integer lifespan,
            final UserRepresentation rep) {
        // first check if user has manage rights
        try {
            auth.users().requireManage();
        } catch (ForbiddenException exception) {
            if (!canCreateGroupMembers(rep)) {
                throw exception;
            }
        }

        String username = rep.getUsername();
        if(realm.isRegistrationEmailAsUsername()) {
            username = rep.getEmail();
        }
        if (ObjectUtil.isBlank(username)) {
            throw ErrorResponse.error("User name is missing", Response.Status.BAD_REQUEST);
        }

        // Double-check duplicated username and email here due to federation
        if (session.users().getUserByUsername(realm, username) != null) {
            throw ErrorResponse.exists("User exists with same username");
        }
        if (rep.getEmail() != null && !realm.isDuplicateEmailsAllowed()) {
            try {
                if(session.users().getUserByEmail(realm, rep.getEmail()) != null) {
                    throw ErrorResponse.exists("User exists with same email");
                }
            } catch (ModelDuplicateException e) {
                throw ErrorResponse.exists("User exists with same email");
            }
        }

        UserProfileProvider profileProvider = session.getProvider(UserProfileProvider.class);

        UserProfile profile = profileProvider.create(USER_API, rep.toAttributes());

        try {
            Response response = UserResource.validateUserProfile(profile, session, auth.adminAuth());
            if (response != null) {
                return response;
            }

            UserModel user = profile.create();

            UserResource.updateUserFromRep(profile, user, rep, session, false);
            RepresentationToModel.createFederatedIdentities(rep, session, realm, user);
            RepresentationToModel.createGroups(rep, realm, user);

            RepresentationToModel.createCredentials(rep, session, realm, user, true);
            adminEvent.operation(OperationType.CREATE).resourcePath(session.getContext().getUri(), user.getId()).representation(rep).success();

            // --- Create link set password ---
            List<String> actions = Collections.singletonList(UserModel.RequiredAction.UPDATE_PASSWORD.name());
            int actionLifespan = lifespan != null ? lifespan : realm.getActionTokenGeneratedByAdminLifespan();
            int expiration = Time.currentTime() + actionLifespan;

            String effectiveClientId = clientId != null ? clientId : Constants.ACCOUNT_MANAGEMENT_CLIENT_ID;
            ClientModel client = realm.getClientByClientId(effectiveClientId);

            // --- Verify redirectUri ---
            if (redirectUri == null) {
                // fallback default
                redirectUri = System.getenv("REDIRECT_URL_DEFAULT");
            }

            String verifiedRedirect = RedirectUtils.verifyRedirectUri(session, redirectUri, client);
            if (verifiedRedirect == null) {
                throw ErrorResponse.error("Invalid redirect URI", Response.Status.BAD_REQUEST);
            }
            redirectUri = verifiedRedirect;

            ExecuteActionsActionToken token = new ExecuteActionsActionToken(
                    user.getId(),
                    user.getEmail(),
                    expiration,
                    actions,
                    redirectUri,
                    Constants.ACCOUNT_MANAGEMENT_CLIENT_ID
            );

            String setPasswordLink = LoginActionsService
                    .actionTokenProcessor(session.getContext().getUri())
                    .queryParam("key", token.serialize(session, realm, session.getContext().getUri()))
                    .build(realm.getName())
                    .toString();

            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().commit();
            }

            Map<String, String> result = new HashMap<>();
            result.put("user_id", user.getId());
            result.put("set_password_link", setPasswordLink);
            return Response.ok(result).build();
                        
        } catch (ModelDuplicateException e) {
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            throw ErrorResponse.exists("User exists with same username or email");
        } catch (PasswordPolicyNotMetException e) {
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            throw ErrorResponse.error("Password policy not met", Response.Status.BAD_REQUEST);
        } catch (ModelException me){
            if (session.getTransactionManager().isActive()) {
                session.getTransactionManager().setRollbackOnly();
            }
            logger.warn("Could not create user", me);
            throw ErrorResponse.error("Could not create user", Response.Status.BAD_REQUEST);
        }
    }

    private boolean canCreateGroupMembers(UserRepresentation rep) {
        if (!Profile.isFeatureEnabled(Profile.Feature.ADMIN_FINE_GRAINED_AUTHZ)) {
            return false;
        }

        List<GroupModel> groups = Optional.ofNullable(rep.getGroups())
                .orElse(Collections.emptyList())
                .stream().map(path -> findGroupByPath(realm, path))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (groups.isEmpty()) {
            return false;
        }

        // if groups is part of the user rep, check if admin has manage_members and manage_membership on each group
        // an exception is thrown in case the current user does not have permissions to manage any of the groups
        for (GroupModel group : groups) {
            auth.groups().requireManageMembers(group);
            auth.groups().requireManageMembership(group);
        }

        return true;
    }

    /**
     * Get representation of the user
     *
     * @param id User id
     * @return
     */
    @Path("{id}")
    public UserResource user(final @PathParam("id") String id) {
        UserModel user = session.users().getUserById(realm, id);
        if (user == null) {
            // we do this to make sure somebody can't phish ids
            if (auth.users().canQuery()) throw new NotFoundException("User not found");
            else throw new ForbiddenException();
        }

        return new UserResource(session, user, auth, adminEvent);
    }

    /**
     * Get users
     *
     * Returns a stream of users, filtered according to query parameters.
     *
     * @param search A String contained in username, first or last name, or email. Default search behavior is prefix-based (e.g., <code>foo</code> or <code>foo*</code>). Use <code>*foo*</code> for infix search and <code>"foo"</code> for exact search.
     * @param last A String contained in lastName, or the complete lastName, if param "exact" is true
     * @param first A String contained in firstName, or the complete firstName, if param "exact" is true
     * @param email A String contained in email, or the complete email, if param "exact" is true
     * @param username A String contained in username, or the complete username, if param "exact" is true
     * @param emailVerified whether the email has been verified
     * @param idpAlias The alias of an Identity Provider linked to the user
     * @param idpUserId The userId at an Identity Provider linked to the user
     * @param firstResult Pagination offset
     * @param maxResults Maximum results size (defaults to 100)
     * @param enabled Boolean representing if user is enabled or not
     * @param briefRepresentation Boolean which defines whether brief representations are returned (default: false)
     * @param exact Boolean which defines whether the params "last", "first", "email" and "username" must match exactly
     * @param searchQuery A query to search for custom attributes, in the format 'key1:value2 key2:value2'
     * @return a non-null {@code Stream} of users
     */
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = KeycloakOpenAPI.Admin.Tags.USERS)
    @Operation( summary = "Get users Returns a stream of users, filtered according to query parameters.")
    public Stream<UserRepresentation> getUsers(
            @Parameter(description = "A String contained in username, first or last name, or email. Default search behavior is prefix-based (e.g., foo or foo*). Use *foo* for infix search and \"foo\" for exact search.") @QueryParam("search") String search,
            @Parameter(description = "A String contained in lastName, or the complete lastName, if param \"exact\" is true") @QueryParam("lastName") String last,
            @Parameter(description = "A String contained in firstName, or the complete firstName, if param \"exact\" is true") @QueryParam("firstName") String first,
            @Parameter(description = "A String contained in email, or the complete email, if param \"exact\" is true") @QueryParam("email") String email,
            @Parameter(description = "A String contained in username, or the complete username, if param \"exact\" is true") @QueryParam("username") String username,
            @Parameter(description = "whether the email has been verified") @QueryParam("emailVerified") Boolean emailVerified,
            @Parameter(description = "The alias of an Identity Provider linked to the user") @QueryParam("idpAlias") String idpAlias,
            @Parameter(description = "The userId at an Identity Provider linked to the user") @QueryParam("idpUserId") String idpUserId,
            @Parameter(description = "Pagination offset") @QueryParam("first") Integer firstResult,
            @Parameter(description = "Maximum results size (defaults to 100)") @QueryParam("max") Integer maxResults,
            @Parameter(description = "Boolean representing if user is enabled or not") @QueryParam("enabled") Boolean enabled,
            @Parameter(description = "Boolean which defines whether brief representations are returned (default: false)") @QueryParam("briefRepresentation") Boolean briefRepresentation,
            @Parameter(description = "Boolean which defines whether the params \"last\", \"first\", \"email\" and \"username\" must match exactly") @QueryParam("exact") Boolean exact,
            @Parameter(description = "A query to search for custom attributes, in the format 'key1:value2 key2:value2'") @QueryParam("q") String searchQuery) {
        UserPermissionEvaluator userPermissionEvaluator = auth.users();

        userPermissionEvaluator.requireQuery();

        firstResult = firstResult != null ? firstResult : -1;
        maxResults = maxResults != null ? maxResults : Constants.DEFAULT_MAX_RESULTS;

        Map<String, String> searchAttributes = searchQuery == null
                ? Collections.emptyMap()
                : SearchQueryUtils.getFields(searchQuery);

        Stream<UserModel> userModels = Stream.empty();
        if (search != null) {
            if (search.startsWith(SEARCH_ID_PARAMETER)) {
                UserModel userModel =
                        session.users().getUserById(realm, search.substring(SEARCH_ID_PARAMETER.length()).trim());
                if (userModel != null) {
                    userModels = Stream.of(userModel);
                }
            } else {
                Map<String, String> attributes = new HashMap<>();
                attributes.put(UserModel.SEARCH, search.trim());
                if (enabled != null) {
                    attributes.put(UserModel.ENABLED, enabled.toString());
                }
                return searchForUser(attributes, realm, userPermissionEvaluator, briefRepresentation, firstResult,
                        maxResults, false);
            }
        } else if (last != null || first != null || email != null || username != null || emailVerified != null
                || idpAlias != null || idpUserId != null || enabled != null || exact != null || !searchAttributes.isEmpty()) {
                    Map<String, String> attributes = new HashMap<>();
                    if (last != null) {
                        attributes.put(UserModel.LAST_NAME, last);
                    }
                    if (first != null) {
                        attributes.put(UserModel.FIRST_NAME, first);
                    }
                    if (email != null) {
                        attributes.put(UserModel.EMAIL, email);
                    }
                    if (username != null) {
                        attributes.put(UserModel.USERNAME, username);
                    }
                    if (emailVerified != null) {
                        attributes.put(UserModel.EMAIL_VERIFIED, emailVerified.toString());
                    }
                    if (idpAlias != null) {
                        attributes.put(UserModel.IDP_ALIAS, idpAlias);
                    }
                    if (idpUserId != null) {
                        attributes.put(UserModel.IDP_USER_ID, idpUserId);
                    }
                    if (enabled != null) {
                        attributes.put(UserModel.ENABLED, enabled.toString());
                    }
                    if (exact != null) {
                        attributes.put(UserModel.EXACT, exact.toString());
                    }

                    attributes.putAll(searchAttributes);

                    return searchForUser(attributes, realm, userPermissionEvaluator, briefRepresentation, firstResult,
                            maxResults, true);
                } else {
                    return searchForUser(new HashMap<>(), realm, userPermissionEvaluator, briefRepresentation,
                            firstResult, maxResults, false);
                }

        return toRepresentation(realm, userPermissionEvaluator, briefRepresentation, userModels);
    }

    /**
     * Returns the number of users that match the given criteria.
     * It can be called in three different ways.
     * 1. Don't specify any criteria and pass {@code null}. The number of all
     * users within that realm will be returned.
     * <p>
     * 2. If {@code search} is specified other criteria such as {@code last} will
     * be ignored even though you set them. The {@code search} string will be
     * matched against the first and last name, the username and the email of a
     * user.
     * <p>
     * 3. If {@code search} is unspecified but any of {@code last}, {@code first},
     * {@code email} or {@code username} those criteria are matched against their
     * respective fields on a user entity. Combined with a logical and.
     *
     * @param search   arbitrary search string for all the fields below. Default search behavior is prefix-based (e.g., <code>foo</code> or <code>foo*</code>). Use <code>*foo*</code> for infix search and <code>"foo"</code> for exact search.
     * @param last     last name filter
     * @param first    first name filter
     * @param email    email filter
     * @param username username filter
     * @param enabled Boolean representing if user is enabled or not
     * @return the number of users that match the given criteria
     */
    @Path("count")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Tag(name = KeycloakOpenAPI.Admin.Tags.USERS)
    @Operation(
            summary = "Returns the number of users that match the given criteria.",
            description = "It can be called in three different ways. " +
                    "1. Don’t specify any criteria and pass {@code null}. The number of all users within that realm will be returned. <p> " +
                    "2. If {@code search} is specified other criteria such as {@code last} will be ignored even though you set them. The {@code search} string will be matched against the first and last name, the username and the email of a user. <p> " +
                    "3. If {@code search} is unspecified but any of {@code last}, {@code first}, {@code email} or {@code username} those criteria are matched against their respective fields on a user entity. Combined with a logical and.")
    public Integer getUsersCount(
            @Parameter(description = "arbitrary search string for all the fields below. Default search behavior is prefix-based (e.g., foo or foo*). Use *foo* for infix search and \"foo\" for exact search.") @QueryParam("search") String search,
            @Parameter(description = "last name filter") @QueryParam("lastName") String last,
            @Parameter(description = "first name filter") @QueryParam("firstName") String first,
            @Parameter(description = "email filter") @QueryParam("email") String email,
            @QueryParam("emailVerified") Boolean emailVerified,
            @Parameter(description = "username filter") @QueryParam("username") String username,
            @Parameter(description = "Boolean representing if user is enabled or not") @QueryParam("enabled") Boolean enabled,
            @QueryParam("q") String searchQuery) {
        UserPermissionEvaluator userPermissionEvaluator = auth.users();
        userPermissionEvaluator.requireQuery();

        Map<String, String> searchAttributes = searchQuery == null
                ? Collections.emptyMap()
                : SearchQueryUtils.getFields(searchQuery);

        if (search != null) {
            if (search.startsWith(SEARCH_ID_PARAMETER)) {
                UserModel userModel = session.users().getUserById(realm, search.substring(SEARCH_ID_PARAMETER.length()).trim());
                return userModel != null && userPermissionEvaluator.canView(userModel) ? 1 : 0;
            } else if (userPermissionEvaluator.canView()) {
                return session.users().getUsersCount(realm, search.trim());
            } else {
                return session.users().getUsersCount(realm, search.trim(), auth.groups().getGroupsWithViewPermission());
            }
        } else if (last != null || first != null || email != null || username != null || emailVerified != null || enabled != null || !searchAttributes.isEmpty()) {
            Map<String, String> parameters = new HashMap<>();
            if (last != null) {
                parameters.put(UserModel.LAST_NAME, last);
            }
            if (first != null) {
                parameters.put(UserModel.FIRST_NAME, first);
            }
            if (email != null) {
                parameters.put(UserModel.EMAIL, email);
            }
            if (username != null) {
                parameters.put(UserModel.USERNAME, username);
            }
            if (emailVerified != null) {
                parameters.put(UserModel.EMAIL_VERIFIED, emailVerified.toString());
            }
            if (enabled != null) {
                parameters.put(UserModel.ENABLED, enabled.toString());
            }
            parameters.putAll(searchAttributes);

            if (userPermissionEvaluator.canView()) {
                return session.users().getUsersCount(realm, parameters);
            } else {
                return session.users().getUsersCount(realm, parameters, auth.groups().getGroupsWithViewPermission());
            }
        } else if (userPermissionEvaluator.canView()) {
            return session.users().getUsersCount(realm);
        } else {
            return session.users().getUsersCount(realm, auth.groups().getGroupsWithViewPermission());
        }
    }

    /**
     * Get representation of the user
     *
     * @param id User id
     * @return
     */
    @Path("profile")
    public UserProfileResource userProfile() {
        return new UserProfileResource(session, auth);
    }

    private Stream<UserRepresentation> searchForUser(Map<String, String> attributes, RealmModel realm, UserPermissionEvaluator usersEvaluator, Boolean briefRepresentation, Integer firstResult, Integer maxResults, Boolean includeServiceAccounts) {
        attributes.put(UserModel.INCLUDE_SERVICE_ACCOUNT, includeServiceAccounts.toString());

        if (!auth.users().canView()) {
            Set<String> groupModels = auth.groups().getGroupsWithViewPermission();

            if (!groupModels.isEmpty()) {
                session.setAttribute(UserModel.GROUPS, groupModels);
            }
        }

        Stream<UserModel> userModels = session.users().searchForUserStream(realm, attributes, firstResult, maxResults);
        return toRepresentation(realm, usersEvaluator, briefRepresentation, userModels);
    }

    private Stream<UserRepresentation> toRepresentation(RealmModel realm, UserPermissionEvaluator usersEvaluator, Boolean briefRepresentation, Stream<UserModel> userModels) {
        boolean briefRepresentationB = briefRepresentation != null && briefRepresentation;
        boolean canViewGlobal = usersEvaluator.canView();

        usersEvaluator.grantIfNoPermission(session.getAttribute(UserModel.GROUPS) != null);
        return userModels.filter(user -> canViewGlobal || usersEvaluator.canView(user))
                .map(user -> {
                    UserRepresentation userRep = briefRepresentationB
                            ? ModelToRepresentation.toBriefRepresentation(user)
                            : ModelToRepresentation.toRepresentation(session, realm, user);
                    userRep.setAccess(usersEvaluator.getAccess(user));
                    return userRep;
                });
    }
}
