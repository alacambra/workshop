package com.samapartners.workshop.client;

import com.samapartners.workshop.sample.SampleStore;
import com.samapartners.workshop.sample.SampleUser;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.hyperledger.fabric.sdk.*;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.hyperledger.fabric.sdk.exception.TransactionException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import java.io.*;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Created by alacambra on 28.11.17.
 */
public class FabricClient {

    private static final String HOST = "10.75.40.65";

    HFClient hfClient;
    Channel channel;
    List<Peer> peers;
    List<Orderer> orderers;
    List<EventHub> eventHubs;


    public static void main(String[] args) {
        new FabricClient().runAll();
    }

    public void runAll() {
        try {

            String organizationName = "org1";
            String mspId = "org1.example.com";
            String organizationMspId = "Org1MSP";


            //HFCLient config
            hfClient = HFClient.createNewInstance();
            hfClient.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
            SampleUser peerOfOrganization1Admin = enroll("Admin", organizationName, organizationMspId);

            hfClient.setUserContext(peerOfOrganization1Admin);
            peers = initPeers(hfClient);
            orderers = initOrderers(hfClient);
            eventHubs = initEventHubs(hfClient);

//            channel = createChannel(hfClient, orderers.get(0), peers.get(0));
            try {
                channel = initChannel(hfClient);
                channel.addPeer(peers.get(0));
                channel.addOrderer(orderers.get(0));
                channel.addEventHub(eventHubs.get(0));
                channel.initialize();
            } catch (TransactionException e) {
                throw new RuntimeException(e);
            }

            ChaincodeID chaincodeID = ChaincodeID.newBuilder()
                    .setName("demo")
                    .setVersion("11")
                    .build();

            installChaincode(hfClient, chaincodeID, "C:/Users/alacambra.SAMA/git/workshop/fabric-client/deployment", peers);
            BlockInfo blockInfo = instantiateChaincode(hfClient, channel, chaincodeID);
            System.out.println("Instantiation on block " + Optional.ofNullable(blockInfo).map(BlockInfo::getBlockNumber).orElse(-1L));

            invoke(chaincodeID, "put", new String[]{"test"});
            String result = query(chaincodeID, "get", new String[]{"none"}, JsonValue::toString).orElse("none");
            System.out.println("result=" + result);

        } catch (CryptoException | InvalidArgumentException e) {
            throw new RuntimeException(e);
        }
    }

    public SampleUser enroll(String username, String organization, String mpsId) {
        SampleUser sampleUser = new SampleUser(username, organization, SampleStore.load());
        sampleUser.setMPSID(mpsId);
        Enrollment enrollment = getEnrollment();
        sampleUser.setEnrollment(enrollment);
        return sampleUser;
    }

    public <T> Optional<T> query(ChaincodeID chaincodeID, String function, String[] args, Function<JsonObject, T> transformer) {
        QueryByChaincodeRequest queryByChaincodeRequest = hfClient.newQueryProposalRequest();
        queryByChaincodeRequest.setArgs(args);
        queryByChaincodeRequest.setFcn(function);
        queryByChaincodeRequest.setChaincodeID(chaincodeID);
        try {
            List<ProposalResponse> proposalResponses = new ArrayList<>(channel.queryByChaincode(queryByChaincodeRequest));

            ProposalResponse proposalResponse = proposalResponses.get(0);
            if (proposalResponse.getStatus() != ChaincodeResponse.Status.SUCCESS) {
                return Optional.empty();
            }

            byte[] bytes = proposalResponses.get(0).getProposalResponse().getResponse().getPayload().toByteArray();
            System.out.println("Received  " + new String(bytes));
            JsonReader jsonReader = Json.createReader(new ByteArrayInputStream(bytes));
            JsonObject jsonObject = jsonReader.readObject();
            T result = transformer.apply(jsonObject);
            return Optional.ofNullable(result);


        } catch (InvalidArgumentException | ProposalException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    private CompletableFuture<BlockInfo> invoke(ChaincodeID chaincodeID, String functionName, String[] args) {

        TransactionProposalRequest transactionProposalRequest = hfClient.newTransactionProposalRequest();
        transactionProposalRequest.setChaincodeID(chaincodeID);
        transactionProposalRequest.setFcn(functionName);
        transactionProposalRequest.setArgs(args);

        Map<String, byte[]> transientProposalData = new HashMap<>();
        transientProposalData.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8));
        transientProposalData.put("method", "TransactionProposalRequest".getBytes(UTF_8));
        transientProposalData.put("result", ":)".getBytes(UTF_8));

        try {
            transactionProposalRequest.setTransientMap(transientProposalData);
            List<ProposalResponse> transactionPropResp = new ArrayList<>(
                    channel.sendTransactionProposal(transactionProposalRequest, channel.getPeers())
            );

            ProposalResponse proposalResponse = transactionPropResp.get(0);

            if (proposalResponse.getStatus() != ChaincodeResponse.Status.SUCCESS) {
                System.out.println("Error: " + proposalResponse.getMessage());
                return null;
            }

            Collection<Set<ProposalResponse>> invokeTRProposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);

            if (invokeTRProposalConsistencySets.size() != 1) {
                throw new RuntimeException(format("Expected only one set of consistent proposal responses but got %d", invokeTRProposalConsistencySets.size()));
            }
            return sendTransactionToOrderer(channel, proposalResponse, orderers);

        } catch (InvalidArgumentException ex) {
            throw new IllegalArgumentException(ex);
        } catch (ProposalException e) {
            throw new RuntimeException(e);
        }
    }

    private Enrollment getEnrollment() {
        return new Enrollment() {
            @Override
            public PrivateKey getKey() {
                //"crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/keystore"
                String pKey = "-----BEGIN PRIVATE KEY-----\n" +
                        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgvd1k1R2/xqnwIP3E\n" +
                        "G2vPSvR0p9jNDBsyGQo1crYEgDKhRANCAASWHAJtzDyAIl/rzIUHs57qWpvis0ht\n" +
                        "RUPcetHHOLnG8aVRRcNH624BXNQSIfGdGrs3LjsW+B3O7GK/0KJ/DBUN\n" +
                        "-----END PRIVATE KEY-----";
                return fromPemToPrivateKey(pKey);
            }

            @Override
            public String getCert() {

                //crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/signcerts/Admin@org1.example.com-cert.pem
                return "-----BEGIN CERTIFICATE-----\n" +
                        "MIICGTCCAcCgAwIBAgIRAIuOQz6wbj5ImyKmf2lH7GUwCgYIKoZIzj0EAwIwczEL\n" +
                        "MAkGA1UEBhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDVNhbiBG\n" +
                        "cmFuY2lzY28xGTAXBgNVBAoTEG9yZzEuZXhhbXBsZS5jb20xHDAaBgNVBAMTE2Nh\n" +
                        "Lm9yZzEuZXhhbXBsZS5jb20wHhcNMTcxMTMwMDc1NjQ2WhcNMjcxMTI4MDc1NjQ2\n" +
                        "WjBbMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMN\n" +
                        "U2FuIEZyYW5jaXNjbzEfMB0GA1UEAwwWQWRtaW5Ab3JnMS5leGFtcGxlLmNvbTBZ\n" +
                        "MBMGByqGSM49AgEGCCqGSM49AwEHA0IABJYcAm3MPIAiX+vMhQeznupam+KzSG1F\n" +
                        "Q9x60cc4ucbxpVFFw0frbgFc1BIh8Z0auzcuOxb4Hc7sYr/Qon8MFQ2jTTBLMA4G\n" +
                        "A1UdDwEB/wQEAwIHgDAMBgNVHRMBAf8EAjAAMCsGA1UdIwQkMCKAIGcW49PV2p1V\n" +
                        "XUKL4R3WtNCyNrUpz9h/0dvvoLPuo4cWMAoGCCqGSM49BAMCA0cAMEQCIFf8+0YL\n" +
                        "gM6ePeOn47Mqw+wUTHFxWgY3Z5eWc28lOkGsAiAUa/8i6jXzZDmwF3SO2BAhtFcL\n" +
                        "/gTSinnkgj2IyZ8vnA==\n" +
                        "-----END CERTIFICATE-----";
            }

            public PrivateKey fromPemToPrivateKey(String pemPrivateKey) {
                PemReader pemReader = new PemReader(new StringReader(pemPrivateKey));
                try {
                    KeyFactory factory = KeyFactory.getInstance("ECDSA", "BC");
                    PemObject pemObject = pemReader.readPemObject();
                    PKCS8EncodedKeySpec privKeySpec = new PKCS8EncodedKeySpec(pemObject.getContent());
                    return factory.generatePrivate(privKeySpec);
                } catch (IOException | NoSuchAlgorithmException | NoSuchProviderException | InvalidKeySpecException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    public List<Peer> initPeers(HFClient hfClient) {

        Properties properties = new Properties();
        String peerUrl = "grpc://" + HOST + ":7051";
        String peerName = "peer0.org1.example.com";

        List<Peer> peers = new ArrayList<>();
        properties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[]{5L, TimeUnit.MINUTES});
        properties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[]{8L, TimeUnit.SECONDS});
//        properties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);

        try {
            peers.add(hfClient.newPeer(peerName, peerUrl, properties));
        } catch (InvalidArgumentException ex) {
            throw new IllegalArgumentException(ex);
        }
        return peers;
    }


    public List<Orderer> initOrderers(HFClient hfClient) {

        String ordererUrl = "grpc://" + HOST + ":7050";

        List<Orderer> orderers = new ArrayList<>();
        Properties ordererProperties = new Properties();
        ordererProperties.setProperty("trustServerCertificate", "true"); //testing environment only NOT FOR PRODUCTION!
        ordererProperties.setProperty("hostnameOverride", "orderer.example.com");
        ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[]{5L, TimeUnit.MINUTES});
        ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[]{8L, TimeUnit.SECONDS});

        try {
            orderers.add(hfClient.newOrderer("orderer.example.com", ordererUrl, ordererProperties));
        } catch (InvalidArgumentException ex) {
            throw new IllegalArgumentException(ex);
        }

        return orderers;
    }

    public static List<EventHub> initEventHubs(HFClient hfClient) {

        List<EventHub> eventHubs = new ArrayList<>();
        String evenHubUrl = "grpc://" + HOST + ":7053";
        String ordererName = "peer0.eventhub.org1.example.com";
        Properties properties = new Properties();
        properties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[]{5L, TimeUnit.MINUTES});
        properties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[]{8L, TimeUnit.SECONDS});

        try {
            eventHubs.add(hfClient.newEventHub(ordererName, evenHubUrl, properties));
        } catch (InvalidArgumentException ex) {
            throw new IllegalArgumentException(ex);
        }

        return eventHubs;
    }

    public Channel initChannel(HFClient hfClient) {

        String channelName = "mychannel";

        Channel channel = null;
        try {
            channel = hfClient.newChannel(channelName);
        } catch (InvalidArgumentException e) {
            throw new RuntimeException(e);
        }

        return channel;
    }

    private Channel createChannel(HFClient hfClient, Orderer orderer, Peer peer) {
        String channelName = "mychannel";
        String path = "C:/Users/alacambra.SAMA/git/go/work/src/github.com/hyperledger/fabric/examples/e2e_cli/channel-artifacts/channel.tx";
        Channel channel;

        try {
            ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File(path));
            channel = hfClient.newChannel(channelName, orderer, channelConfiguration, hfClient.getChannelConfigurationSignature(channelConfiguration, hfClient.getUserContext()));
            channel.joinPeer(peer);
        } catch (IOException | ProposalException | InvalidArgumentException | TransactionException e) {
            throw new RuntimeException(e);
        }

        return channel;

    }

    private void runTestQuery(Channel channel, HFClient hfClient) {

        QueryByChaincodeRequest queryByChaincodeRequest = hfClient.newQueryProposalRequest();
        queryByChaincodeRequest.setArgs(new String[]{"arg"});
        queryByChaincodeRequest.setFcn("test");
        ChaincodeID chaincodeID = ChaincodeID.newBuilder()
                .setName("demo")
                .setVersion("1")
                .build();
        queryByChaincodeRequest.setChaincodeID(chaincodeID);
        try {
            List<ProposalResponse> proposalResponses = new ArrayList<>(channel.queryByChaincode(queryByChaincodeRequest));
            System.out.println("Response " + proposalResponses.get(0)
                    .getProposalResponse()
                    .getResponse()
                    .getPayload()
                    .toString("UTF-8"));
        } catch (InvalidArgumentException | ProposalException | UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    private void query(ChaincodeID chaincodeID, String functionName, String[] args) {

        QueryByChaincodeRequest queryByChaincodeRequest = hfClient.newQueryProposalRequest();
        queryByChaincodeRequest.setArgs(new String[]{"arg"});
        queryByChaincodeRequest.setFcn("test");
        queryByChaincodeRequest.setChaincodeID(chaincodeID);
        try {
            List<ProposalResponse> proposalResponses = new ArrayList<>(channel.queryByChaincode(queryByChaincodeRequest));
            System.out.println("Response " + proposalResponses.get(0)
                    .getProposalResponse()
                    .getResponse()
                    .getPayload()
                    .toString("UTF-8"));
        } catch (InvalidArgumentException | ProposalException | UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    public List<ProposalResponse> installChaincode(HFClient hfClient, ChaincodeID chaincodeID, String chaincodeSourceLocation, Collection<Peer> endorsementPeers) {

        InstallProposalRequest installProposalRequest = hfClient.newInstallProposalRequest();
        installProposalRequest.setChaincodeID(chaincodeID);

        try {
            installProposalRequest.setChaincodeSourceLocation(new File(chaincodeSourceLocation));
        } catch (InvalidArgumentException e) {
        }

        installProposalRequest.setChaincodeLanguage(TransactionRequest.Type.JAVA);
        installProposalRequest.setChaincodeVersion(chaincodeID.getVersion());

        try {
            return new ArrayList<>(hfClient.sendInstallProposal(installProposalRequest, endorsementPeers));
        } catch (ProposalException | InvalidArgumentException e) {
            throw new RuntimeException(e);
        }

    }


    public BlockInfo instantiateChaincode(HFClient hfClient, Channel channel, ChaincodeID chaincodeID) {
        try {

            InstantiateProposalRequest proposalRequest = hfClient.newInstantiationProposalRequest();

            proposalRequest.setChaincodeID(chaincodeID);
            proposalRequest.setFcn("init");
            proposalRequest.setChaincodeLanguage(TransactionRequest.Type.JAVA);
            proposalRequest.setArgs(new ArrayList<>(0));
            proposalRequest.setUserContext(hfClient.getUserContext());

            Map<String, byte[]> tm = new HashMap<>();
            tm.put("HyperLedgerFabric", "InstantiateProposalRequest:JavaSDK".getBytes(UTF_8));
            tm.put("method", "InstantiateProposalRequest".getBytes(UTF_8));

            try {
                proposalRequest.setTransientMap(tm);
            } catch (InvalidArgumentException e) {
                e.printStackTrace();
            }

            List<ProposalResponse> responses = new ArrayList<>(channel.sendInstantiationProposal(proposalRequest));
            ProposalResponse proposalResponse = responses.get(0);

            if (proposalResponse.isVerified() && proposalResponse.getStatus() == ProposalResponse.Status.SUCCESS) {
                try {
                    return sendTransactionToOrderer(channel, proposalResponse, channel.getOrderers()).get();
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }

        } catch (ProposalException | InvalidArgumentException e) {
            e.printStackTrace();
            return null;
        }

        return null;
    }

    private CompletableFuture<BlockInfo> sendTransactionToOrderer(Channel channel, ProposalResponse proposalsResult, Collection<Orderer> orderer) {

        return channel.sendTransaction(Collections.singletonList(proposalsResult), orderers)
                .thenApply(transactionEvent -> {
                    String transactionId = transactionEvent.getTransactionID();
                    try {
                        return channel.queryBlockByTransactionID(transactionId);
                    } catch (ProposalException | InvalidArgumentException e) {
                        e.printStackTrace();
                    }

                    return null;
                });
    }
}
