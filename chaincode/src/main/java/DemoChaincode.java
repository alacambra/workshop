import org.hyperledger.fabric.shim.ChaincodeBase;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Created by alacambra on 28.11.17.
 */
public class DemoChaincode extends ChaincodeBase {

    @Override
    public Response init(ChaincodeStub chaincodeStub) {
        System.out.println("Chaincode started");
        return newSuccessResponse("all ok", "all Ok".getBytes());
    }

    @Override
    public Response invoke(ChaincodeStub chaincodeStub) {
        return newSuccessResponse("all ok", "all Ok".getBytes());
    }

    public static void main(String[] args) {
        new DemoChaincode().start(args);
    }

}
