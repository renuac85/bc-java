package org.bouncycastle.est.jcajce;


import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CRL;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateException;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.bouncycastle.est.ESTClientSourceProvider;
import org.bouncycastle.est.Source;
import org.bouncycastle.est.TLSAcceptedIssuersSource;
import org.bouncycastle.est.TLSAuthorizer;
import org.bouncycastle.est.TLSHostNameAuthorizer;

public class DefaultESTClientSourceProvider
    implements ESTClientSourceProvider
{

    private final TLSAcceptedIssuersSource tlsAcceptedIssuersSource;
    private final TLSAuthorizer serverTLSAuthorizer;
    private final KeyManagerFactory keyManagerFactory;
    private final TLSHostNameAuthorizer<SSLSession> hostNameAuthorizer;


    private SSLSocketFactory sslSocketFactory;

    public DefaultESTClientSourceProvider(
        TLSAcceptedIssuersSource tlsAcceptedIssuersSource,
        TLSAuthorizer serverTLSAuthorizer,
        KeyManagerFactory keyManagerFactory,
        TLSHostNameAuthorizer<SSLSession> hostNameAuthorizer)
        throws GeneralSecurityException
    {
        this.tlsAcceptedIssuersSource = tlsAcceptedIssuersSource;
        this.serverTLSAuthorizer = serverTLSAuthorizer;
        this.hostNameAuthorizer = hostNameAuthorizer;
        this.keyManagerFactory = keyManagerFactory;
        sslSocketFactory = createFactory();
    }

    /**
     * Return an ESTClientSSLSocketProvider that uses the the default SSLSocketProvider and a host name verifier.
     *
     * @param hostNameAuthorizer The host name authorizer. (Can be null for no hostname verification.)
     * @return ESTClientSSLSocketProvider
     * @throws Exception
     */
    public static ESTClientSourceProvider getUsingDefaultSSLSocketFactory(TLSHostNameAuthorizer<SSLSession> hostNameAuthorizer)
        throws GeneralSecurityException
    {
        return new DefaultESTClientSourceProvider(null, null, null, hostNameAuthorizer)
        {
            @Override
            public SSLSocketFactory createFactory()
            {
                return (SSLSocketFactory)SSLSocketFactory.getDefault();
            }
        };
    }

    /**
     * Return an ESTClientSSLSocketProvider that uses the the default SSLSocketProvider and a host name verifier.
     *
     * @param keyManagerFactory  The keymanager factory supplying the client keys.
     * @param hostNameAuthorizer The host name authorizer. (Can be null for no hostname verification.)
     * @return ESTClientSSLSocketProvider
     * @throws Exception
     */
    public static ESTClientSourceProvider getUsingDefaultSSLSocketFactory(KeyManagerFactory keyManagerFactory, TLSHostNameAuthorizer<SSLSession> hostNameAuthorizer)
        throws Exception
    {
        return new DefaultESTClientSourceProvider(null, null, keyManagerFactory, hostNameAuthorizer)
        {
            @Override
            public SSLSocketFactory createFactory()
            {
                return (SSLSocketFactory)SSLSocketFactory.getDefault();
            }
        };
    }

    public static TLSAuthorizer getCertPathTLSAuthorizer(final CRL revocationList)
    {
        // TODO must accept array of revocation lists.

        return new TLSAuthorizer()
        {
            public void authorize(Set<TrustAnchor> acceptedIssuers, X509Certificate[] chain, String authType)
                throws CertificateException
            {
                try
                {
                    // From BC JSSE.
                    // TODO Review.
                    CertStore certStore = CertStore.getInstance("Collection",
                        new CollectionCertStoreParameters(Arrays.asList(chain)), "BC");

                    CertPathBuilder pathBuilder = CertPathBuilder.getInstance("PKIX", "BC");

                    X509CertSelector constraints = new X509CertSelector();

                    constraints.setCertificate(chain[0]);


                    PKIXBuilderParameters param = new PKIXBuilderParameters(acceptedIssuers, constraints);
                    param.addCertStore(certStore);
                    if (revocationList != null)
                    {
                        param.setRevocationEnabled(true);
                        param.addCertStore(
                            CertStore.getInstance(
                                "Collection",
                                new CollectionCertStoreParameters(Arrays.asList(revocationList)
                                )));
                    }
                    else
                    {
                        param.setRevocationEnabled(false);
                    }

                    PKIXCertPathValidatorResult result = (PKIXCertPathValidatorResult)pathBuilder.build(param);
                }
                catch (GeneralSecurityException e)
                {
                    throw new CertificateException("unable to process certificates: " + e.getMessage(), e);
                }
            }
        };
    }

    /**
     * Creates the SSLSocketFactory.
     *
     * @return A SSLSocketFactory instance.
     * @throws Exception
     */
    public SSLSocketFactory createFactory()
        throws GeneralSecurityException
    {
        SSLContext ctx = SSLContext.getInstance("TLS");
        X509TrustManager tm = new X509TrustManager()
        {
            public void checkClientTrusted(X509Certificate[] x509Certificates, String authType)
                throws CertificateException
            {
                // For clients.
            }

            public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
                throws CertificateException
            {
                if (serverTLSAuthorizer == null)
                {
                    throw new CertificateException(
                        "No serverTLSAuthorizer specified, if you wish to have no validation then you must supply an instance that does nothing."
                    );
                }

                serverTLSAuthorizer.authorize(tlsAcceptedIssuersSource != null ? tlsAcceptedIssuersSource.anchors() : null, x509Certificates, s);
            }

            public X509Certificate[] getAcceptedIssuers()
            {
                if (tlsAcceptedIssuersSource != null)
                {
                    Set<TrustAnchor> tas = tlsAcceptedIssuersSource.anchors();
                    X509Certificate[] c = new X509Certificate[tas.size()];
                    int j = 0;
                    for (Iterator it = tas.iterator(); it.hasNext(); )
                    {
                        TrustAnchor ta = (TrustAnchor)it.next();
                        c[j++] = ta.getTrustedCert();
                    }
                    return c;
                }
                return new X509Certificate[0];
            }
        };

        ctx.init((keyManagerFactory != null) ? keyManagerFactory.getKeyManagers() : null, new TrustManager[]{tm}, new SecureRandom());
        return ctx.getSocketFactory();
    }

    public Source wrapSocket(Socket plainSocket, String host, int port)
        throws IOException
    {
        SSLSocket sock = (SSLSocket)sslSocketFactory.createSocket(plainSocket, host, port, true);
        sock.setUseClientMode(true);
        sock.startHandshake();
        if (hostNameAuthorizer != null && !hostNameAuthorizer.verified(host, sock.getSession()))
        {
            throw new IOException("Hostname was not verified: " + host);
        }
        return new SSLSocketSource(sock);
    }
}
