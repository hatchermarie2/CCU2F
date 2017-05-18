/*
*******************************************************************************
*   FIDO CCU2F Authenticator
*   (c) 2017 tsenger
*   based on FIDO U2F Authenticator by Ledger 
*
*  Licensed under the Apache License, Version 2.0 (the "License");
*  you may not use this file except in compliance with the License.
*  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software
*  distributed under the License is distributed on an "AS IS" BASIS,
*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*  limitations under the License.
*******************************************************************************
*/

package de.tsenger.u2f;

import javacard.framework.JCSystem;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.ECKey;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.KeyAgreement;
import javacard.security.KeyBuilder;
import javacard.security.KeyPair;
import javacard.security.MessageDigest;
import javacard.security.RandomData;

import com.nxp.id.jcopx.KeyAgreementX;
import com.nxp.id.jcopx.KeyBuilderX;

public class FIDOCCImplementation implements FIDOAPI {

    private static KeyPair keyPair;
    private static AESKey macKey1, macKey2;
    private static AESKey drngSeed1, drngSeed2;
    private static RandomData random;
    private static byte[] scratch;    
    private static KeyAgreement ecMultiplyHelper;
    private static MessageDigest sha256;


    public FIDOCCImplementation() {
    	
    	random = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
    	
        scratch = JCSystem.makeTransientByteArray((short)32, JCSystem.CLEAR_ON_DESELECT);
        
        keyPair = new KeyPair(
            (ECPublicKey)KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PUBLIC, KeyBuilder.LENGTH_EC_FP_256, false),
            (ECPrivateKey)KeyBuilder.buildKey(KeyBuilder.TYPE_EC_FP_PRIVATE, KeyBuilder.LENGTH_EC_FP_256, false));
        Secp256r1.setCommonCurveParameters((ECKey)keyPair.getPrivate());
        Secp256r1.setCommonCurveParameters((ECKey)keyPair.getPublic());
                
        // Initialize the unique seed for DRNG function       
        drngSeed1 = (AESKey)KeyBuilderX.buildKey(KeyBuilderX.TYPE_AES_STATIC, KeyBuilder.LENGTH_AES_128, false);
        drngSeed2 = (AESKey)KeyBuilderX.buildKey(KeyBuilderX.TYPE_AES_STATIC, KeyBuilder.LENGTH_AES_128, false);
        random.generateData(scratch, (short)0, (short)16);
        drngSeed1.setKey(scratch, (short)0);        
        random.generateData(scratch, (short)0, (short)16);        
        drngSeed2.setKey(scratch, (short)0);                
        
        // Initialize the unique keys for MAC function
        macKey1 = (AESKey)KeyBuilderX.buildKey(KeyBuilderX.TYPE_AES_STATIC, KeyBuilder.LENGTH_AES_128, false);
        macKey2 = (AESKey)KeyBuilderX.buildKey(KeyBuilderX.TYPE_AES_STATIC, KeyBuilder.LENGTH_AES_128, false);
        random.generateData(scratch, (short)0, (short)16);
        macKey1.setKey(scratch, (short)0);
        random.generateData(scratch, (short)0, (short)16);
        macKey2.setKey(scratch, (short)0);
        
        //Delete Key in scratch
        random.generateData(scratch, (short)0, (short)16);
        
        // Initialize ecMultiplier 
        ecMultiplyHelper = KeyAgreementX.getInstance(KeyAgreementX.ALG_EC_SVDP_DH_PLAIN_XY, false);
        
        // Initialize SHA256 (used as DRNG and MAC calculation)
        sha256 = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);
    }
    
    /**
	 * Generate Public Key from given Private Key. Uses the Key Agreement API of Java card.
	 * @param pointOutputBuffer Output buffer
	 * @param offset Offset of the output buffer
	 * @return Length of the final EC point
	 */
	private short generatePublicKeyPoint(byte[] pointOutputBuffer, short offset){
		ecMultiplyHelper.init(keyPair.getPrivate());
		return ecMultiplyHelper.generateSecret(Secp256r1.SECP256R1_G, (short) 0, (short) 65, pointOutputBuffer, offset);
	}
	
	private void generatePrivateKey(byte[] nonceBuffer, short nonceBufferOffset, byte[] applicationParameter, short applicationParameterOffset) {
		Util.arrayFillNonAtomic(scratch, (short)0, (short)32, (byte)0x00);
		drngSeed1.getKey(scratch, (short) 0);
		sha256.reset();
		sha256.update(scratch, (short) 0, (short) 16);
		sha256.update(applicationParameter, applicationParameterOffset, (short) 32);
		sha256.update(nonceBuffer, nonceBufferOffset, (short) 32);
		drngSeed2.getKey(scratch, (short) 0);
		sha256.doFinal(scratch, (short) 0, (short) 16, scratch, (short) 0);
		
	}
	
	private void calcMAC(byte[] applicationParameter, short applicationParameterOffset, byte[] nonceBuffer, short nonceBufferOffset) {
		macKey1.getKey(scratch, (short) 0);
		sha256.reset();
		sha256.update(scratch, (short)0, (short) 16);
		sha256.update(applicationParameter, applicationParameterOffset, (short) 32);
		sha256.update(nonceBuffer, nonceBufferOffset, (short) 32);
		macKey2.getKey(scratch, (short) 0);
		sha256.doFinal(scratch, (short) 0, (short) 16, scratch, (short)0);		
	}

    public short generateKeyAndWrap(byte[] applicationParameter, short applicationParameterOffset, ECPrivateKey generatedPrivateKey, byte[] publicKey, short publicKeyOffset, byte[] keyHandle, short keyHandleOffset) {
        // Generate 32 byte nonce
    	random.generateData(keyHandle, keyHandleOffset, (short) 32);
    	
    	//Generate PrivKey 
    	generatePrivateKey(keyHandle, keyHandleOffset, applicationParameter, applicationParameterOffset);
    	
    	// Set private Key S, before generating Public Key
    	((ECPrivateKey)keyPair.getPrivate()).setS(scratch, (short) 0, (short) 32);
    	
    	generatePublicKeyPoint(publicKey, publicKeyOffset);
    	
    	// erase Private Key
    	Util.arrayFillNonAtomic(scratch, (short)0, (short)32, (byte)0x00);
    	((ECPrivateKey)keyPair.getPrivate()).setS(scratch, (short) 0, (short) 32);
    	
    	calcMAC(applicationParameter, applicationParameterOffset, keyHandle, keyHandleOffset);
    	Util.arrayCopyNonAtomic(scratch, (short) 0, keyHandle, (short) (keyHandleOffset + 32), (short) 32);
        
        return (short)64;
    }

    public boolean unwrap(byte[] keyHandle, short keyHandleOffset, short keyHandleLength, byte[] applicationParameter, short applicationParameterOffset, ECPrivateKey unwrappedPrivateKey) {
        // Verify
    	
    	calcMAC(applicationParameter, applicationParameterOffset, keyHandle, keyHandleOffset);
    	
    	//Compare MAC
    	if (Util.arrayCompare(scratch, (short) 0, keyHandle, (short)(keyHandleOffset+32), (short)32)!=0) {
    		return false;
    	}
    	
    	//only get key if signing is required
        if (unwrappedPrivateKey != null) {
        	//Regenerate PrivKey 
        	generatePrivateKey(keyHandle, keyHandleOffset, applicationParameter, applicationParameterOffset);
        	
            unwrappedPrivateKey.setS(scratch, (short)0, (short)32);
        }
        Util.arrayFillNonAtomic(scratch, (short)0, (short)32, (byte)0x00);
        return true;
    }

}