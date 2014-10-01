package ee.cyber.sdsb.common.conf;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;

import javax.xml.bind.JAXBElement;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ee.cyber.sdsb.common.CodedException;
import ee.cyber.sdsb.common.cert.CertChain;
import ee.cyber.sdsb.common.conf.globalconf.*;
import ee.cyber.sdsb.common.identifier.CentralServiceId;
import ee.cyber.sdsb.common.identifier.ClientId;
import ee.cyber.sdsb.common.identifier.GlobalGroupId;
import ee.cyber.sdsb.common.identifier.SecurityCategoryId;
import ee.cyber.sdsb.common.identifier.SecurityServerId;
import ee.cyber.sdsb.common.identifier.ServiceId;
import ee.cyber.sdsb.common.util.CertUtils;

import static ee.cyber.sdsb.common.ErrorCodes.*;
import static ee.cyber.sdsb.common.util.CryptoUtils.*;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

public class GlobalConfImpl extends AbstractXmlConf<GlobalConfType>
        implements GlobalConfProvider {

    private static final Logger LOG =
            LoggerFactory.getLogger(GlobalConfImpl.class);

    // Cached items, filled at conf reload
    private Map<X500Name, X509Certificate> subjectsAndCaCerts =
            new HashMap<>();
    private Map<X509Certificate, NameExtractorType> caCertsAndNameExtractors =
            new HashMap<>();
    private Map<X509Certificate, List<OcspInfoType>> caCertsAndOcspData =
            new HashMap<>();
    private Map<ClientId, Set<String>> memberAddresses = new HashMap<>();
    private Map<ClientId, Set<byte[]>> memberAuthCerts = new HashMap<>();
    private Map<String, SecurityServerType> serverByAuthCert = new HashMap<>();
    private Map<SecurityServerId, Set<ClientId>> securityServerClients =
            new HashMap<>();

    private List<X509Certificate> verificationCaCerts = new ArrayList<>();
    private Set<String> knownAddresses = new HashSet<>();

    public GlobalConfImpl(String confFileName) {
        super(ObjectFactory.class, confFileName,
                GlobalConfSchemaValidator.class);
        try {
            cacheCaCerts();
            cacheKnownAddresses();
            cacheSecurityServers();
        } catch (Exception e) {
            throw translateException(e);
        }
    }

    @Override
    public String getSdsbInstance() {
        return confType.getInstanceIdentifier();
    }

    @Override
    public ServiceId getServiceId(CentralServiceId centralServiceId) {
        if (!getSdsbInstance().equals(centralServiceId.getSdsbInstance())) {
            throw new CodedException(X_INTERNAL_ERROR,
                    "Incompatible SDSB instances (%s, %s)",
                    getSdsbInstance(), centralServiceId.getSdsbInstance());
        }

        for (CentralServiceType centralServiceType :
                confType.getCentralService()) {
            if (centralServiceType.getImplementingService() == null) {
                continue;
            }

            if (centralServiceId.getServiceCode().equals(
                    centralServiceType.getServiceCode())) {
                return centralServiceType.getImplementingService();
            }
        }

        throw new CodedException(X_INTERNAL_ERROR,
                "Cannot find implementing service for central service '%s'",
                centralServiceId);
    }

    @Override
    public List<ClientId> getMembers() {
        List<ClientId> clients = new ArrayList<>();

        for (MemberType member : confType.getMember()) {
            clients.add(createMemberId(member));

            for (SubsystemType subsystem : member.getSubsystem()) {
                clients.add(createSubsystemId(member, subsystem));
            }
        }

        return clients;
    }

    @Override
    public List<CentralServiceId> getCentralServices() {
        List<CentralServiceId> centralServices = new ArrayList<>();

        for (CentralServiceType centralService : confType.getCentralService()) {
            centralServices.add(
                    CentralServiceId.create(confType.getInstanceIdentifier(),
                            centralService.getServiceCode()));
        }

        return centralServices;
    }

    @Override
    public String getProviderAddress(X509Certificate authCert)
            throws Exception {
        if (authCert == null) {
            return null;
        }

        byte[] inputCertHash = certHash(authCert);

        for (SecurityServerType securityServer : confType.getSecurityServer()) {
            for (byte[] hash : securityServer.getAuthCertHash()) {
                if (Arrays.equals(inputCertHash, hash)) {
                    return securityServer.getAddress();
                }
            }
        }

        return null;
    }

    @Override
    public Collection<String> getProviderAddress(ClientId clientId) {
        if (clientId == null) {
            return null;
        }

        return memberAddresses.get(clientId);
    }

    @Override
    public List<String> getOcspResponderAddresses(X509Certificate member)
            throws Exception {
        List<String> responders = new ArrayList<>();

        List<OcspInfoType> caOcspData =
                caCertsAndOcspData.get(getCaCert(member));
        for (OcspInfoType caOcspItem : caOcspData) {
            if (isNotBlank(caOcspItem.getUrl())) {
                responders.add(caOcspItem.getUrl().trim());
            }
        }

        String uri = CertUtils.getOcspResponderUriFromCert(member);
        if (uri != null) {
            responders.add(uri.trim());
        }

        return responders;
    }

    @Override
    public List<X509Certificate> getOcspResponderCertificates() {
        List<X509Certificate> responderCerts = new ArrayList<>();
        try {
            for (List<OcspInfoType> ocspTypes : caCertsAndOcspData.values()) {
                for (OcspInfoType ocspType : ocspTypes) {
                    if (ocspType.getCert() != null) {
                        responderCerts.add(readCertificate(ocspType.getCert()));
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Error while getting OCSP responder certificates: ", e);
            return Collections.emptyList();
        }

        return responderCerts;
    }

    @Override
    public X509Certificate getCaCert(X509Certificate memberCert)
            throws Exception {
        if (memberCert == null) {
            throw new IllegalArgumentException(
                    "Member certificate must be present to find CA cert!");
        }

        X509CertificateHolder memberCertHolder =
                new X509CertificateHolder(memberCert.getEncoded());
        X509Certificate caCert =
                subjectsAndCaCerts.get(memberCertHolder.getIssuer());
        if (caCert != null) {
            return caCert;
        }

        throw new CodedException(X_INTERNAL_ERROR,
                "Unable to find CA certificate for member %s (issuer = %s)",
                memberCertHolder.getSubject(), memberCertHolder.getIssuer());
    }

    @Override
    public List<X509Certificate> getAllCaCerts() throws CertificateException {
        return new ArrayList<>(subjectsAndCaCerts.values());
    }

    @Override
    public CertChain getCertChain(X509Certificate subject)
            throws Exception {
        if (subject == null) {
            throw new IllegalArgumentException(
                    "Member certificate must be present to find cert chain!");
        }

        List<X509Certificate> chain = new ArrayList<>();
        chain.add(subject);

        X509Certificate ca = getCaCertForSubject(subject);
        while (ca != null) {
            chain.add(ca);
            ca = getCaCertForSubject(ca);
        }

        if (chain.size() < 2) { // did not found any CA certs
            return null;
        }

        return CertChain.create(
                chain.toArray(new X509Certificate[chain.size()]));
    }

    @Override
    public boolean isOcspResponderCert(X509Certificate ca,
            X509Certificate ocspCert) {
        List<OcspInfoType> caOcspData = caCertsAndOcspData.get(ca);
        try {
            for (OcspInfoType ocspType : caOcspData) {
                if (ocspType.getCert() == null) {
                    continue;
                }

                X509Certificate cert = readCertificate(ocspType.getCert());
                if (cert.equals(ocspCert)) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.error("Couldn't read OCSP responder certificate under type", e);
        }

        return false;
    }

    @Override
    public X509Certificate[] getAuthTrustChain() {
        try {
            List<X509Certificate> certs = getAllCaCerts();
            return certs.toArray(new X509Certificate[certs.size()]);
        } catch (Exception e) {
            throw translateException(e);
        }
    }

    @Override
    public boolean hasAuthCert(X509Certificate cert, SecurityServerId server)
            throws Exception {
        String base64 = encodeBase64(certHash(cert));

        SecurityServerType serverType = serverByAuthCert.get(base64);
        if (server == null) {
            return serverType != null;
        } else if (serverType == null) {
            return false;
        }

        MemberType owner = getOwner(serverType);
        SecurityServerId foundServerId =
                SecurityServerId.create(confType.getInstanceIdentifier(),
                        owner.getMemberClass(), owner.getMemberCode(),
                        serverType.getServerCode());

        return foundServerId.equals(server);
    }

    @Override
    public boolean authCertMatchesMember(X509Certificate cert,
            ClientId memberId) throws Exception {
        byte[] inputCertHash = certHash(cert);

        Set<byte[]> registeredHashes = memberAuthCerts.get(memberId);
        if (registeredHashes == null || registeredHashes.isEmpty()) {
            return false;
        }

        for (byte[] hash : registeredHashes) {
            if (Arrays.equals(inputCertHash, hash)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public Set<SecurityCategoryId> getProvidedCategories(
            X509Certificate authCert) throws Exception {
        String base64 = encodeBase64(certHash(authCert));

        SecurityServerType server = serverByAuthCert.get(base64);
        if (server == null) {
            throw new IllegalArgumentException("No security servers "
                    + "correspond to authentication certificate");
        }

        Set<SecurityCategoryId> ret = new HashSet<>();
        for (String cat: server.getSecurityCategory()) {
            ret.add(SecurityCategoryId.create(
                    confType.getInstanceIdentifier(), cat));
        }

        return ret;
    }

    @Override
    public ClientId getSubjectName(X509Certificate cert) throws Exception {
        X509Certificate caCert = getCaCert(cert);
        NameExtractorType nameExtractor = caCertsAndNameExtractors.get(caCert);
        if (nameExtractor == null) {
            throw new CodedException(X_INTERNAL_ERROR,
                    "Could not find name extractor for certificate "
                            + cert.getSerialNumber());
        }

        Method m;
        try {
            m = getMethodFromClassName(nameExtractor.getMethodName(),
                    X509Certificate.class);
        } catch (ClassNotFoundException e) {
            throw new CodedException(X_INTERNAL_ERROR,
                    "Could not find name extractor: %s",
                    nameExtractor.getMethodName());
        } catch (Exception e) {
            LOG.error("Could not get name extractor method '"
                    + nameExtractor + "'", e);
            throw new CodedException(X_INTERNAL_ERROR, e);
        }

        Object result;
        try {
            result = m.invoke(null /* Static, no instance */, cert);
        } catch (Exception e) {
            Throwable t = (e instanceof InvocationTargetException)
                    ? e.getCause() : e;
            String message = "Error during extraction of subject name from "
                    + "certificate '" + cert.getSubjectDN() + "' using "
                    + "name extractor '" + nameExtractor + "'";
            LOG.error(message, t);
            throw new CodedException(X_INCORRECT_CERTIFICATE,
                    message + ": " + t.getMessage());
        }

        if (result == null) {
            throw new CodedException(X_INCORRECT_CERTIFICATE,
                    "Could not get SubjectName from certificate " +
                            cert.getSubjectDN());
        }

        if (result instanceof String) {
            return ClientId.create(confType.getInstanceIdentifier(),
                    nameExtractor.getMemberClass(), (String) result);
        } else if (result instanceof String[]) {
            String[] parts = (String[]) result;
            if (parts.length == 2) {
                return ClientId.create(confType.getInstanceIdentifier(),
                        parts[0], parts[1]);
            }
        } else if (result instanceof ClientId) {
            return (ClientId) result;
        }

        throw new CodedException(X_INTERNAL_ERROR,
                "Unexpected result from name extractor: "
                        + result.getClass());
    }

    @Override
    public List<X509Certificate> getTspCertificates()
            throws Exception {
        List<X509Certificate> tspCerts = new ArrayList<>();
        for (ApprovedTspType tspType : confType.getApprovedTsp()) {
            if (tspType.getCert() != null) {
                tspCerts.add(readCertificate(tspType.getCert()));
            }
        }
        return tspCerts;
    }

    @Override
    public Set<String> getKnownAddresses() {
        return knownAddresses;
    }

    @Override
    public List<X509Certificate> getVerificationCaCerts() {
        return verificationCaCerts;
    }

    @Override
    public boolean isSubjectInGlobalGroup(ClientId subjectId,
            GlobalGroupId groupId) {
        GlobalGroupType group = findGlobalGroup(groupId);
        if (group == null) {
            return false;
        }

        for (ClientId member : group.getMember()) {
            if (member.equals(subjectId)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isSecurityServerClient(ClientId clientId,
            SecurityServerId securityServerId) {
        return securityServerClients.containsKey(securityServerId) &&
            securityServerClients.get(securityServerId).contains(clientId);
    }

    @Override
    public String getManagementRequestServiceAddress() {
        GlobalSettingsType gs = confType.getGlobalSettings();
        return gs.getManagementRequestServiceAddress();
    }

    @Override
    public ClientId getManagementRequestService() {
        GlobalSettingsType gs = confType.getGlobalSettings();
        return gs.getManagementRequestServiceId();
    }

    /**
     * Returns SSL certificates of central servers.
     */
    @Override
    public X509Certificate getCentralServerSslCertificate() throws Exception {
        byte[] certBytes = confType.getCentralServerSslCert();
        if (certBytes != null) {
            return readCertificate(certBytes);
        }

        return null;
    }

    /** Checks that the conf is fresh enough -- that the last modifier date
     * is not older than CONF_FRESHNESS_TIME_MINUTES.
     * @throws CodedException with error code X_OUTDATED_GLOBALCONF
     * if conf is too old
     */
    @Override
    public void verifyUpToDate() {
        File file = new File(confFileName);
        if (file.exists()) { // if not, error is thrown elsewhere
            long lastModified = file.lastModified();
            long time = System.currentTimeMillis();
            if (time - lastModified >
                    ConfConstants.CONF_FRESHNESS_TIME_MINUTES * 1000 * 60) {
                throw new CodedException(X_OUTDATED_GLOBALCONF,
                        "GlobalConf is out of date");
            }
        }
    }

   // ------------------------------------------------------------------------

    private GlobalGroupType findGlobalGroup(GlobalGroupId groupId) {
        if (!groupId.getSdsbInstance().equals(
                confType.getInstanceIdentifier())) {
            return null;
        }

        for (GlobalGroupType group : confType.getGlobalGroup()) {
            if (group.getGroupCode().equals(groupId.getGroupCode())) {
                return group;
            }
        }

        return null;
    }

    private void cacheCaCerts() throws CertificateException, IOException {
        List<X509Certificate> allCaCerts = new ArrayList<>();

        for (PkiType pkiType : confType.getPki()) {
            List<CaInfoType> topCAs = Arrays.asList(pkiType.getTopCA());
            List<CaInfoType> intermediateCAs = pkiType.getIntermediateCA();

            cacheOcspData(topCAs);
            cacheOcspData(intermediateCAs);

            List<X509Certificate> pkiCaCerts = new ArrayList<>();

            pkiCaCerts.addAll(getTopOrIntermediateCaCerts(topCAs));
            pkiCaCerts.addAll(getTopOrIntermediateCaCerts(intermediateCAs));

            Boolean authenticationOnly = pkiType.isAuthenticationOnly();
            if (authenticationOnly == null || !authenticationOnly) {
                verificationCaCerts.addAll(pkiCaCerts);
            }

            NameExtractorType nameExtractor = pkiType.getNameExtractor();

            for (X509Certificate pkiCaCert : pkiCaCerts) {
                caCertsAndNameExtractors.put(pkiCaCert, nameExtractor);
            }
            allCaCerts.addAll(pkiCaCerts);
        }

        for (X509Certificate cert : allCaCerts) {
            X509CertificateHolder certHolder =
                    new X509CertificateHolder(cert.getEncoded());
            subjectsAndCaCerts.put(certHolder.getSubject(), cert);
        }
    }

    private void cacheKnownAddresses() {
        for (SecurityServerType server : confType.getSecurityServer()) {
            if (isNotBlank(server.getAddress())) {
                knownAddresses.add(server.getAddress());
            }
        }
    }

    private void cacheSecurityServers() {
        // Map of XML ID fields mapped to client IDs
        Map<String, ClientId> clientIds = getClientIds();

        for (SecurityServerType securityServer : confType.getSecurityServer()) {
            // Cache the server.
            for (byte[] certHash: securityServer.getAuthCertHash()) {
                serverByAuthCert.put(encodeBase64(certHash),
                        securityServer);
            }

            // Add owner of the security server.
            MemberType owner = (MemberType) securityServer.getOwner();
            addServerClient(createMemberId(owner), securityServer);

            // Add clients of the security server.
            for (JAXBElement<?> client : securityServer.getClient()) {
                Object val = client.getValue();

                if (val instanceof MemberType) {
                    addServerClient(createMemberId((MemberType) val),
                            securityServer);
                } else if (val instanceof SubsystemType) {
                    addServerClient(
                            clientIds.get(((SubsystemType) val).getId()),
                            securityServer);
                }
            }
        }
    }

    private void addServerClient(ClientId client, SecurityServerType server) {
        // Add the mapping from client to security server address.
        if (isNotBlank(server.getAddress())) {
            addToMap(memberAddresses, client, server.getAddress());
        }

        // Add the mapping from client to authentication certificate.
        for (byte[] authCert : server.getAuthCertHash()) {
            addToMap(memberAuthCerts, client, authCert);
        }

        MemberType owner = getOwner(server);
        SecurityServerId securityServerId = SecurityServerId.create(
                getSdsbInstance(), owner.getMemberClass(),
                owner.getMemberCode(), server.getServerCode());

        addToMap(securityServerClients, securityServerId, client);
    }

    private static <K, V> void addToMap(Map<K, Set<V>> map, K key, V value) {
        Set<V> coll = map.get(key);
        if (coll == null) {
            coll = new HashSet<>();
            map.put(key, coll);
        }
        coll.add(value);
    }

    private Map<String, ClientId> getClientIds() {
        Map<String, ClientId> ret = new HashMap<>();

        for (MemberType member : confType.getMember()) {
            ret.put(member.getId(), createMemberId(member));

            for (SubsystemType subsystem : member.getSubsystem()) {
                ret.put(subsystem.getId(),
                        createSubsystemId(member, subsystem));
            }
        }

        return ret;
    }

    private ClientId createMemberId(MemberType member) {
        return ClientId.create(confType.getInstanceIdentifier(),
                member.getMemberClass(), member.getMemberCode());
    }

    private ClientId createSubsystemId(MemberType member,
            SubsystemType subsystem) {
        return ClientId.create(confType.getInstanceIdentifier(),
                member.getMemberClass(), member.getMemberCode(),
                subsystem.getSubsystemCode());
    }

    private void cacheOcspData(List<CaInfoType> typesUnderCA)
            throws CertificateException, IOException {
        for (CaInfoType caType : typesUnderCA) {
            X509Certificate cert = readCertificate(caType.getCert());
            List<OcspInfoType> caOcspTypes = caType.getOcsp();
            caCertsAndOcspData.put(cert, caOcspTypes);
        }
    }

    private X509Certificate getCaCertForSubject(X509Certificate subject)
            throws Exception {
        X509CertificateHolder certHolder =
                new X509CertificateHolder(subject.getEncoded());
        if (certHolder.getSubject().equals(certHolder.getIssuer())) {
            return null;
        }

        return subjectsAndCaCerts.get(certHolder.getIssuer());
    }

    private static List<X509Certificate> getTopOrIntermediateCaCerts(
            List<CaInfoType> typesUnderCA)
                    throws CertificateException, IOException {
        List<X509Certificate> certs = new ArrayList<>();
        for (CaInfoType caType : typesUnderCA) {
            certs.add(readCertificate(caType.getCert()));
        }
        return certs;
    }

    /**
     * Returns a method from the class and method name string. Assumes, the
     * class name is in form [class].[method]
     */
    private static Method getMethodFromClassName(String classAndMethodName,
            Class<?>... parameterTypes) throws Exception {
        int lastIdx = classAndMethodName.lastIndexOf('.');
        if (lastIdx == -1) {
            throw new IllegalArgumentException(
                    "classAndMethodName must be in form of <class>.<method>");
        }

        String className = classAndMethodName.substring(0, lastIdx);
        String methodName = classAndMethodName.substring(lastIdx + 1);

        Class<?> clazz = Class.forName(className);
        return clazz.getMethod(methodName, parameterTypes);
    }

    private static MemberType getOwner(SecurityServerType serverType) {
        if (!(serverType.getOwner() instanceof MemberType)) {
            throw new RuntimeException("Server owner must be member");
        }

        return (MemberType) serverType.getOwner();
    }
}