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
            HFClient hfClient = HFClient.createNewInstance();
            hfClient.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite());
            SampleUser peerOfOrganization1Admin = enroll("Admin", organizationName, organizationMspId);

            hfClient.setUserContext(peerOfOrganization1Admin);
            peers = initPeers(hfClient);
            orderers = initOrderers(hfClient);
            eventHubs = initEventHubs(hfClient);


            Channel channel;
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
                    .setVersion("1")
//                    .setPath("/Users/albertlacambra1/git/samaworkshop/fabric-client/")
                    .build();

            installChaincode(hfClient, chaincodeID, "C:/Users/alacambra.SAMA/git/workshop/fabric-client", peers);
            BlockInfo blockInfo = instantiateChaincode(hfClient, channel, chaincodeID);
            System.out.println("Instantion on block " + Optional.ofNullable(blockInfo).map(BlockInfo::getBlockNumber).orElse(-1L));

            runTestQuery(channel, hfClient);

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
                        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgOTAyvyKCd6kPZrPh\n" +
                        "mT4jTmeU7724zr33DAM+jWGOQKWhRANCAATGrUKaP23FUXq/eiVuYTLtl8fysWaW\n" +
                        "2YjSbPiEdpiVgkPz+1XUGmdaJ5k642V64m1cxe5rZeNN0wlnGJFmz+bD\n" +
                        "-----END PRIVATE KEY-----";
                return fromPemToPrivateKey(pKey);
            }

            @Override
            public String getCert() {

                //crypto-config/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp/signcerts/Admin@org1.example.com-cert.pem
                return "-----BEGIN CERTIFICATE-----\n" +
                        "MIICGDCCAb+gAwIBAgIQVyaxhVNk8NzLsPqL6p4P0DAKBggqhkjOPQQDAjBzMQsw\n" +
                        "CQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNU2FuIEZy\n" +
                        "YW5jaXNjbzEZMBcGA1UEChMQb3JnMS5leGFtcGxlLmNvbTEcMBoGA1UEAxMTY2Eu\n" +
                        "b3JnMS5leGFtcGxlLmNvbTAeFw0xNzExMjkyMTIwMTJaFw0yNzExMjcyMTIwMTJa\n" +
                        "MFsxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1T\n" +
                        "YW4gRnJhbmNpc2NvMR8wHQYDVQQDDBZBZG1pbkBvcmcxLmV4YW1wbGUuY29tMFkw\n" +
                        "EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEK0wg3D7kkLn/k4Ly895hGSrjcEedm36N\n" +
                        "3XmXBzqA25yo4XvA0HQJR+6rplSScZS95rSigiaKRcTqUH9lWOe4IaNNMEswDgYD\n" +
                        "VR0PAQH/BAQDAgeAMAwGA1UdEwEB/wQCMAAwKwYDVR0jBCQwIoAgBT19srNLwFNM\n" +
                        "q9QfzOSe/OfzF0grHetaAtZZ9+iA8WgwCgYIKoZIzj0EAwIDRwAwRAIgC4rXxhba\n" +
                        "QRT+3u7XCGCnqBXBc3mWaJRcapKyaNtiITMCIF+c9+CJesUBmenM9oyzy42XW0nZ\n" +
                        "sxCPk/ZP44EH10Tc\n" +
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

    public List<Peer> initPeers(HFClient client) {

        Properties properties = new Properties();
        String peerUrl = "grpc://192.168.99.100:7051";
        String peerName = "peer0.org1.example.com";

        List<Peer> peers = new ArrayList<>();
        properties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[]{5L, TimeUnit.MINUTES});
        properties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[]{8L, TimeUnit.SECONDS});
//        properties.put("grpc.NettyChannelBuilderOption.maxInboundMessageSize", 9000000);

        try {
            peers.add(client.newPeer(peerName, peerUrl, properties));
        } catch (InvalidArgumentException ex) {
            throw new IllegalArgumentException(ex);
        }
        return peers;
    }


    public List<Orderer> initOrderers(HFClient client) {

        String ordererUrl = "grpc://192.168.99.100:7050";

        List<Orderer> orderers = new ArrayList<>();
        Properties ordererProperties = new Properties();
        ordererProperties.setProperty("trustServerCertificate", "true"); //testing environment only NOT FOR PRODUCTION!
        ordererProperties.setProperty("hostnameOverride", "orderer.example.com");
        ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[]{5L, TimeUnit.MINUTES});
        ordererProperties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[]{8L, TimeUnit.SECONDS});

        try {
            orderers.add(client.newOrderer("orderer.example.com", ordererUrl, ordererProperties));
        } catch (InvalidArgumentException ex) {
            throw new IllegalArgumentException(ex);
        }

        return orderers;
    }

    public static List<EventHub> initEventHubs(HFClient client) {

        List<EventHub> eventHubs = new ArrayList<>();
        String evenHubUrl = "grpc://192.168.99.100:7053";
        String ordererName = "peer0.eventhub.org1.example.com";
        Properties properties = new Properties();
        properties.put("grpc.NettyChannelBuilderOption.keepAliveTime", new Object[]{5L, TimeUnit.MINUTES});
        properties.put("grpc.NettyChannelBuilderOption.keepAliveTimeout", new Object[]{8L, TimeUnit.SECONDS});

        try {
            eventHubs.add(client.newEventHub(ordererName, evenHubUrl, properties));
        } catch (InvalidArgumentException ex) {
            throw new IllegalArgumentException(ex);
        }

        return eventHubs;
    }

    public Channel initChannel(HFClient client) {

        String channelName = "mychannel";

        Channel channel = null;
        try {
            channel = client.newChannel(channelName);
        } catch (InvalidArgumentException e) {
            throw new RuntimeException(e);
        }

        return channel;
    }

    private Channel createChannel(HFClient client, Orderer orderer, Peer peer) {
        String channelName = "mychannel";
        String path = "C:/Users/alacambra.SAMA/git/go/work/src/github.com/hyperledger/fabric/examples/e2e_cli/channel-artifacts/channel.tx";
        Channel channel;

        try {
            ChannelConfiguration channelConfiguration = new ChannelConfiguration(new File(path));
            channel = client.newChannel(channelName, orderer, channelConfiguration, client.getChannelConfigurationSignature(channelConfiguration, client.getUserContext()));
            channel.joinPeer(peer);
        } catch (IOException | ProposalException | InvalidArgumentException | TransactionException e) {
            throw new RuntimeException(e);
        }

        return channel;

    }

    private void runTestQuery(Channel channel, HFClient client) {

        QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
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

    public List<ProposalResponse> installChaincode(HFClient client, ChaincodeID chaincodeID, String chaincodeSourceLocation, Collection<Peer> endorsementPeers) {

        InstallProposalRequest installProposalRequest = client.newInstallProposalRequest();
        installProposalRequest.setChaincodeID(chaincodeID);

        try {
            installProposalRequest.setChaincodeSourceLocation(new File(chaincodeSourceLocation));
        } catch (InvalidArgumentException e) {
        }

        installProposalRequest.setChaincodeLanguage(TransactionRequest.Type.JAVA);
        installProposalRequest.setChaincodeVersion(chaincodeID.getVersion());

        try {
            return new ArrayList<>(client.sendInstallProposal(installProposalRequest, endorsementPeers));
        } catch (ProposalException | InvalidArgumentException e) {
            throw new RuntimeException(e);
        }

    }


    public BlockInfo instantiateChaincode(HFClient client, Channel channel, ChaincodeID chaincodeID) {
        try {

            InstantiateProposalRequest proposalRequest = client.newInstantiationProposalRequest();

            proposalRequest.setChaincodeID(chaincodeID);
            proposalRequest.setFcn("init");
            proposalRequest.setChaincodeLanguage(TransactionRequest.Type.JAVA);
            proposalRequest.setArgs(new ArrayList<>(0));
            proposalRequest.setUserContext(client.getUserContext());

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
