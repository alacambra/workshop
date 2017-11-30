package com.samapartners.workshop.chaincode;

import org.hyperledger.fabric.shim.Chaincode;
import org.hyperledger.fabric.shim.ChaincodeBase;
import org.hyperledger.fabric.shim.ChaincodeStub;

/**
 * Created by alacambra on 29.11.17.
 */
public class CarManagementChaincode extends ChaincodeBase {
    @Override
    public Response init(ChaincodeStub chaincodeStub) {
        return null;
    }

    @Override
    public Response invoke(ChaincodeStub chaincodeStub) {
        return null;
    }


    private void queryAllCars(ChaincodeStub stub){

    }

    private void createCar(ChaincodeStub stub){

    }

    private void queryCarProperties(ChaincodeStub stub){

    }

    private void transferCar(ChaincodeStub stub){

    }
}
