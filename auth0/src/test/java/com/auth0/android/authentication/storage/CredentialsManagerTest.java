package com.auth0.android.authentication.storage;

import com.auth0.android.authentication.AuthenticationAPIClient;
import com.auth0.android.authentication.AuthenticationException;
import com.auth0.android.callback.BaseCallback;
import com.auth0.android.request.ParameterizableRequest;
import com.auth0.android.result.Credentials;
import com.auth0.android.result.CredentialsMock;

import org.hamcrest.core.Is;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Date;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(constants = com.auth0.android.auth0.BuildConfig.class, sdk = 21, manifest = Config.NONE)
public class CredentialsManagerTest {

    @Mock
    private AuthenticationAPIClient client;
    @Mock
    private Storage storage;
    @Mock
    private BaseCallback<Credentials, CredentialsManagerException> callback;
    @Mock
    private ParameterizableRequest<Credentials, AuthenticationException> request;
    @Captor
    private ArgumentCaptor<Credentials> credentialsCaptor;
    @Captor
    private ArgumentCaptor<CredentialsManagerException> exceptionCaptor;
    @Rule
    public ExpectedException exception = ExpectedException.none();

    private CredentialsManager manager;
    @Captor
    private ArgumentCaptor<BaseCallback<Credentials, AuthenticationException>> requestCallbackCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        CredentialsManager credentialsManager = new CredentialsManager(client, storage);
        manager = spy(credentialsManager);
        //Needed to test expiration verification
        doReturn(CredentialsMock.CURRENT_TIME_MS).when(manager).getCurrentTimeInMillis();
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) throws Throwable {
                String idToken = invocation.getArgumentAt(0, String.class);
                String accessToken = invocation.getArgumentAt(1, String.class);
                String type = invocation.getArgumentAt(2, String.class);
                String refreshToken = invocation.getArgumentAt(3, String.class);
                Date expiresAt = invocation.getArgumentAt(4, Date.class);
                String scope = invocation.getArgumentAt(5, String.class);
                return new CredentialsMock(idToken, accessToken, type, refreshToken, expiresAt, scope);
            }
        }).when(manager).recreateCredentials(anyString(), anyString(), anyString(), anyString(), any(Date.class), anyString());
    }

    @Test
    public void shouldSaveCredentialsInStorage() throws Exception {
        long expirationTime = CredentialsMock.CURRENT_TIME_MS + 123456 * 1000;
        Credentials credentials = new CredentialsMock("idToken", "accessToken", "type", "refreshToken", new Date(expirationTime), "scope");
        manager.saveCredentials(credentials);

        verify(storage).store("com.auth0.id_token", "idToken");
        verify(storage).store("com.auth0.access_token", "accessToken");
        verify(storage).store("com.auth0.refresh_token", "refreshToken");
        verify(storage).store("com.auth0.token_type", "type");
        verify(storage).store("com.auth0.expires_at", expirationTime);
        verify(storage).store("com.auth0.scope", "scope");
    }

    @Test
    public void shouldThrowOnSetIfCredentialsDoesNotHaveIdTokenOrAccessToken() throws Exception {
        exception.expect(CredentialsManagerException.class);
        exception.expectMessage("Credentials must have a valid date of expiration and a valid access_token or id_token value.");

        Credentials credentials = new CredentialsMock(null, null, "type", "refreshToken", 123456L);
        manager.saveCredentials(credentials);
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    public void shouldThrowOnSetIfCredentialsDoesNotHaveExpiresAt() throws Exception {
        exception.expect(CredentialsManagerException.class);
        exception.expectMessage("Credentials must have a valid date of expiration and a valid access_token or id_token value.");

        Date date = null;
        Credentials credentials = new CredentialsMock("idToken", "accessToken", "type", "refreshToken", date, "scope");
        manager.saveCredentials(credentials);
    }

    @Test
    public void shouldNotThrowOnSetIfCredentialsHaveAccessTokenAndExpiresIn() throws Exception {
        Credentials credentials = new CredentialsMock(null, "accessToken", "type", "refreshToken", 123456L);
        manager.saveCredentials(credentials);
    }

    @Test
    public void shouldNotThrowOnSetIfCredentialsHaveIdTokenAndExpiresIn() throws Exception {
        Credentials credentials = new CredentialsMock("idToken", null, "type", "refreshToken", 123456L);
        manager.saveCredentials(credentials);
    }

    @Test
    public void shouldFailOnGetCredentialsWhenNoAccessTokenOrIdTokenWasSaved() throws Exception {
        verifyNoMoreInteractions(client);

        when(storage.retrieveString("com.auth0.id_token")).thenReturn(null);
        when(storage.retrieveString("com.auth0.access_token")).thenReturn(null);
        when(storage.retrieveString("com.auth0.refresh_token")).thenReturn("refreshToken");
        when(storage.retrieveString("com.auth0.token_type")).thenReturn("type");
        long expirationTime = CredentialsMock.CURRENT_TIME_MS + 123456L * 1000;
        when(storage.retrieveLong("com.auth0.expires_at")).thenReturn(expirationTime);
        when(storage.retrieveString("com.auth0.scope")).thenReturn("scope");

        manager.getCredentials(callback);

        verify(callback).onFailure(exceptionCaptor.capture());
        CredentialsManagerException exception = exceptionCaptor.getValue();
        assertThat(exception, is(notNullValue()));
        assertThat(exception.getMessage(), is("No Credentials were previously set."));
    }

    @Test
    public void shouldFailOnGetCredentialsWhenNoExpirationTimeWasSaved() throws Exception {
        verifyNoMoreInteractions(client);

        when(storage.retrieveString("com.auth0.id_token")).thenReturn("idToken");
        when(storage.retrieveString("com.auth0.access_token")).thenReturn("accessToken");
        when(storage.retrieveString("com.auth0.refresh_token")).thenReturn("refreshToken");
        when(storage.retrieveString("com.auth0.token_type")).thenReturn("type");
        when(storage.retrieveLong("com.auth0.expires_at")).thenReturn(null);
        when(storage.retrieveString("com.auth0.scope")).thenReturn("scope");

        manager.getCredentials(callback);

        verify(callback).onFailure(exceptionCaptor.capture());
        CredentialsManagerException exception = exceptionCaptor.getValue();
        assertThat(exception, is(notNullValue()));
        assertThat(exception.getMessage(), is("No Credentials were previously set."));
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    @Test
    public void shouldFailOnGetCredentialsWhenExpiredAndNoRefreshTokenWasSaved() throws Exception {
        verifyNoMoreInteractions(client);

        when(storage.retrieveString("com.auth0.id_token")).thenReturn("idToken");
        when(storage.retrieveString("com.auth0.access_token")).thenReturn("accessToken");
        when(storage.retrieveString("com.auth0.refresh_token")).thenReturn(null);
        when(storage.retrieveString("com.auth0.token_type")).thenReturn("type");
        long expirationTime = CredentialsMock.CURRENT_TIME_MS; //Same as current time --> expired
        when(storage.retrieveLong("com.auth0.expires_at")).thenReturn(expirationTime);
        when(storage.retrieveString("com.auth0.scope")).thenReturn("scope");

        manager.getCredentials(callback);

        verify(callback).onFailure(exceptionCaptor.capture());
        CredentialsManagerException exception = exceptionCaptor.getValue();
        assertThat(exception, is(notNullValue()));
        assertThat(exception.getMessage(), is("Credentials have expired and no Refresh Token was available to renew them."));
    }

    @Test
    public void shouldGetNonExpiredCredentialsFromStorage() throws Exception {
        verifyNoMoreInteractions(client);

        when(storage.retrieveString("com.auth0.id_token")).thenReturn("idToken");
        when(storage.retrieveString("com.auth0.access_token")).thenReturn("accessToken");
        when(storage.retrieveString("com.auth0.refresh_token")).thenReturn("refreshToken");
        when(storage.retrieveString("com.auth0.token_type")).thenReturn("type");
        long expirationTime = CredentialsMock.CURRENT_TIME_MS + 123456L * 1000;
        when(storage.retrieveLong("com.auth0.expires_at")).thenReturn(expirationTime);
        when(storage.retrieveString("com.auth0.scope")).thenReturn("scope");

        manager.getCredentials(callback);
        verify(callback).onSuccess(credentialsCaptor.capture());
        Credentials retrievedCredentials = credentialsCaptor.getValue();

        assertThat(retrievedCredentials, is(notNullValue()));
        assertThat(retrievedCredentials.getAccessToken(), is("accessToken"));
        assertThat(retrievedCredentials.getIdToken(), is("idToken"));
        assertThat(retrievedCredentials.getRefreshToken(), is("refreshToken"));
        assertThat(retrievedCredentials.getType(), is("type"));
        assertThat(retrievedCredentials.getExpiresIn(), is(123456L));
        assertThat(retrievedCredentials.getExpiresAt(), is(notNullValue()));
        assertThat(retrievedCredentials.getExpiresAt().getTime(), is(expirationTime));
        assertThat(retrievedCredentials.getScope(), is("scope"));
    }

    @Test
    public void shouldGetNonExpiredCredentialsFromStorageWhenOnlyIdTokenIsAvailable() throws Exception {
        verifyNoMoreInteractions(client);

        when(storage.retrieveString("com.auth0.id_token")).thenReturn("idToken");
        when(storage.retrieveString("com.auth0.access_token")).thenReturn(null);
        when(storage.retrieveString("com.auth0.refresh_token")).thenReturn("refreshToken");
        when(storage.retrieveString("com.auth0.token_type")).thenReturn("type");
        long expirationTime = CredentialsMock.CURRENT_TIME_MS + 123456L * 1000;
        when(storage.retrieveLong("com.auth0.expires_at")).thenReturn(expirationTime);
        when(storage.retrieveString("com.auth0.scope")).thenReturn("scope");

        manager.getCredentials(callback);
        verify(callback).onSuccess(credentialsCaptor.capture());
        Credentials retrievedCredentials = credentialsCaptor.getValue();

        assertThat(retrievedCredentials, is(notNullValue()));
        assertThat(retrievedCredentials.getAccessToken(), is(nullValue()));
        assertThat(retrievedCredentials.getIdToken(), is("idToken"));
        assertThat(retrievedCredentials.getRefreshToken(), is("refreshToken"));
        assertThat(retrievedCredentials.getType(), is("type"));
        assertThat(retrievedCredentials.getExpiresIn(), is(123456L));
        assertThat(retrievedCredentials.getExpiresAt(), is(notNullValue()));
        assertThat(retrievedCredentials.getExpiresAt().getTime(), is(expirationTime));
        assertThat(retrievedCredentials.getScope(), is("scope"));
    }

    @Test
    public void shouldGetNonExpiredCredentialsFromStorageWhenOnlyAccessTokenIsAvailable() throws Exception {
        verifyNoMoreInteractions(client);

        when(storage.retrieveString("com.auth0.id_token")).thenReturn(null);
        when(storage.retrieveString("com.auth0.access_token")).thenReturn("accessToken");
        when(storage.retrieveString("com.auth0.refresh_token")).thenReturn("refreshToken");
        when(storage.retrieveString("com.auth0.token_type")).thenReturn("type");
        Long expirationTime = CredentialsMock.CURRENT_TIME_MS + 123456L * 1000;
        when(storage.retrieveLong("com.auth0.expires_at")).thenReturn(expirationTime);
        when(storage.retrieveString("com.auth0.scope")).thenReturn("scope");

        manager.getCredentials(callback);
        verify(callback).onSuccess(credentialsCaptor.capture());
        Credentials retrievedCredentials = credentialsCaptor.getValue();

        assertThat(retrievedCredentials, is(notNullValue()));
        assertThat(retrievedCredentials.getAccessToken(), is("accessToken"));
        assertThat(retrievedCredentials.getIdToken(), is(nullValue()));
        assertThat(retrievedCredentials.getRefreshToken(), is("refreshToken"));
        assertThat(retrievedCredentials.getType(), is("type"));
        assertThat(retrievedCredentials.getExpiresIn(), is(123456L));
        assertThat(retrievedCredentials.getExpiresAt(), is(notNullValue()));
        assertThat(retrievedCredentials.getExpiresAt().getTime(), is(expirationTime));
        assertThat(retrievedCredentials.getScope(), is("scope"));
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    @Test
    public void shouldGetAndSuccessfullyRenewExpiredCredentials() throws Exception {
        when(storage.retrieveString("com.auth0.id_token")).thenReturn("idToken");
        when(storage.retrieveString("com.auth0.access_token")).thenReturn("accessToken");
        when(storage.retrieveString("com.auth0.refresh_token")).thenReturn("refreshToken");
        when(storage.retrieveString("com.auth0.token_type")).thenReturn("type");
        long expirationTime = CredentialsMock.CURRENT_TIME_MS; //Same as current time --> expired
        when(storage.retrieveLong("com.auth0.expires_at")).thenReturn(expirationTime);
        when(storage.retrieveString("com.auth0.scope")).thenReturn("scope");
        when(client.renewAuth("refreshToken")).thenReturn(request);

        manager.getCredentials(callback);
        verify(request).start(requestCallbackCaptor.capture());

        //Trigger success
        Credentials renewedCredentials = mock(Credentials.class);
        requestCallbackCaptor.getValue().onSuccess(renewedCredentials);
        verify(callback).onSuccess(credentialsCaptor.capture());

        Credentials retrievedCredentials = credentialsCaptor.getValue();
        assertThat(retrievedCredentials, is(notNullValue()));
        assertThat(retrievedCredentials, is(renewedCredentials));
    }

    @SuppressWarnings("UnnecessaryLocalVariable")
    @Test
    public void shouldGetAndFailToRenewExpiredCredentials() throws Exception {
        when(storage.retrieveString("com.auth0.id_token")).thenReturn("idToken");
        when(storage.retrieveString("com.auth0.access_token")).thenReturn("accessToken");
        when(storage.retrieveString("com.auth0.refresh_token")).thenReturn("refreshToken");
        when(storage.retrieveString("com.auth0.token_type")).thenReturn("type");
        long expirationTime = CredentialsMock.CURRENT_TIME_MS; //Same as current time --> expired
        when(storage.retrieveLong("com.auth0.expires_at")).thenReturn(expirationTime);
        when(storage.retrieveString("com.auth0.scope")).thenReturn("scope");
        when(client.renewAuth("refreshToken")).thenReturn(request);

        manager.getCredentials(callback);
        verify(request).start(requestCallbackCaptor.capture());

        //Trigger failure
        AuthenticationException authenticationException = mock(AuthenticationException.class);
        requestCallbackCaptor.getValue().onFailure(authenticationException);
        verify(callback).onFailure(exceptionCaptor.capture());

        CredentialsManagerException exception = exceptionCaptor.getValue();
        assertThat(exception, is(notNullValue()));
        assertThat(exception.getCause(), Is.<Throwable>is(authenticationException));
        assertThat(exception.getMessage(), is("An error occurred while trying to use the Refresh Token to renew the Credentials."));
    }
}