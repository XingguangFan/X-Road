package ee.cyber.sdsb.signer.protocol.message;

import java.io.Serializable;

import lombok.Value;

@Value
public class SetCertStatus implements Serializable {

    private final String certId;
    private final String status;

}