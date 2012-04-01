package com.android.internal.telephony.gsm;

import android.os.Message;
import android.util.Log;
import java.security.InvalidParameterException;
import android.telephony.PhoneNumberUtils;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.GsmAlphabet;
public class SmspRecord {


// static fields
static final byte DCS_MASK = -9;//-0x9t;

static final byte DEST_ADDR_MASK = -2;// -0x2t;

static final String EMAIL_GW_TAG = "EMAIL SETTINGS";

static final int FOOTER_SIZE_BYTES = 0x1c;

static final int MAX_ADDR_SIZE_BYTES = 0xa;

static final byte PID_MASK = 5;//0x5t;

static final byte SC_ADDR_MASK = -3;//-0x3t;

static final int SMSP_DCS = 0x1a;

static final int SMSP_DEST_ADDR_LENGTH = 0x1;

static final int SMSP_PID = 0x19;

static final int SMSP_SC_ADDR_LENGTH = 0xd;

static final int SMSP_VALID_PERIOD = 0x1b;

static final String TAG = "GSM";

static final byte VALID_PERIOD_MASK = -17;//-0x11t;


//# instance fields
private String mAlphaTag;

private byte mDCS;

private String mDestAddr;

private Message mOnMessageComplete;

private byte mPID;

private byte mParamInd;

private int mRecSizeBytes;

private String mScAddr;

private int mValidPer;


//# direct methods
public SmspRecord(SmspRecord smsp) {
    mAlphaTag = "";
    mDestAddr = "";
    mScAddr = "";
    mOnMessageComplete = null;
    mParamInd = -1;
    mAlphaTag = smsp.mAlphaTag;
    mDestAddr = smsp.mDestAddr;
    mScAddr = smsp.mScAddr;
    mRecSizeBytes = smsp.mRecSizeBytes;
    mValidPer = smsp.mValidPer;
    mParamInd = smsp.mParamInd;
    mPID = smsp.mPID;
    mDCS = smsp.mDCS;
}

public SmspRecord(byte[] record) {
    mAlphaTag = "";
    mDestAddr = "";
    mScAddr = "";
    mOnMessageComplete = null;
    mParamInd = -1;
    parseRecord( record );
}

private void parseRecord(byte[] record) {
	try
	{
		mRecSizeBytes = record.length;
		Log.i("GSM", "[parseRecord] Record Length is " + mRecSizeBytes );
		int footerOffset = mRecSizeBytes - 0x1c;
		mAlphaTag = IccUtils.adnStringFieldToString(record, 0, footerOffset);
		Log.i("GSM", "[parseRecord] Alpha Tag is " + mAlphaTag);
		mParamInd = record[footerOffset];

		if ( (mParamInd & -2) == mParamInd ) {
			int destAddrIndex = footerOffset + 1;
			int destAddrLength = record[destAddrIndex] & 0xff;
			Log.i("GSM", "[parseRecord] Destination Address Length is " + destAddrLength );
			if ( destAddrLength > 11) {
				mDestAddr = "";
				return;
			}
			mDestAddr = PhoneNumberUtils.calledPartyBCDToString(record, destAddrIndex + 1, destAddrLength);
			Log.i("GSM", "[parseRecord] Destination Address is " + mDestAddr);		
		}

		if ( (mParamInd & -3) == mParamInd ) {
			int scAddrIndex = footerOffset + 0x0d;
			int scAddrLength = record[scAddrIndex] & 0xff;
			Log.i("GSM", "[parseRecord] Service Address Length is " + scAddrLength);
			if ( scAddrLength > 11 ) {
				mScAddr = "";
				return;
			}
			mScAddr = PhoneNumberUtils.calledPartyBCDToString(record, scAddrIndex + 1, scAddrLength);
			Log.i("GSM", "[parseRecord] Service Centre is " + mScAddr);
		}
		if ( (mParamInd & -5) == mParamInd ) {
			mPID = record[footerOffset + 0x19];
			Log.i("GSM", "[parseRecord] Protocol Identifier is " + mPID);
		}
		if ( (mParamInd & -9) == mParamInd ) {
			mDCS = record[footerOffset + 0x1a];
			Log.i("GSM", "[parseRecord] Data Coding Scheme is " + mDCS);
		}
		if ( (mParamInd & -17) == mParamInd ) {
			mValidPer = record[footerOffset + 0x1b];
			Log.i("GSM", "[parseRecord] Validity Period is " + mValidPer);
		}
	}
	catch (RuntimeException ex) {
		Log.e("GSM", "Error parsing SmspRecord", ex);
		mParamInd = -1;
	}
}

public byte[] buildData() {
	int footerOffset = mRecSizeBytes - 0x1c;
	byte[] smspData = null;

	if ( footerOffset < 0 ) {
		Log.e("GSM", "[buildData] Record size is " + mRecSizeBytes );
	}
	else {
		if ( mAlphaTag.length() > footerOffset ) {
			Log.e("GSM", "[buildData] Max length of tag is " + footerOffset );
		}
		else {
			smspData = new byte[mRecSizeBytes];
			for ( int i=0; i<mRecSizeBytes; i++ ) {
				smspData[i]=-1;
			}
			if ( mAlphaTag.length() == 0) {
				Log.w("GSM", "[buildData] Empty alpha tag");
			}
			else {
				byte[] byteTag = GsmAlphabet.stringToGsm8BitPacked(mAlphaTag);
				System.arraycopy(byteTag, 0, smspData, 0, byteTag.length);
				Log.i("GSM", "[buildData] Alpha Tag number of bytes is " + byteTag.length);
			}
			Log.i("GSM", "[buildData] Parameter Indicator is " + mParamInd);
			smspData[footerOffset] = mParamInd;
			if ( (mParamInd & -2) == mParamInd ) {
				byte[] bcdDestAddr = PhoneNumberUtils.numberToCalledPartyBCD( mDestAddr );
				Log.i("GSM", "[buildData] Dest Addr number of bytes is " + bcdDestAddr.length);
				smspData[footerOffset + 1] = (byte) bcdDestAddr.length;
				System.arraycopy(bcdDestAddr, 0, smspData, footerOffset + 2, bcdDestAddr.length);
			}
			if ( (mParamInd & -3) == mParamInd ) {
				byte[] bcdScAddr = PhoneNumberUtils.numberToCalledPartyBCD( mScAddr );
				Log.i("GSM", "[buildData] Service Centre number of bytes is " + bcdScAddr.length);
				smspData[footerOffset + 0xd] = (byte) bcdScAddr.length;
				System.arraycopy(bcdScAddr, 0, smspData, footerOffset + 0xe, bcdScAddr.length);
			}
			if ( (mParamInd & -5) == mParamInd ) {
				smspData[footerOffset + 0x19] = mPID;
			}
			if ( (mParamInd & -9) == mParamInd ) {
				smspData[footerOffset + 0x1a] = mDCS;
			}
			if ( (mParamInd & -17) == mParamInd ) {
				smspData[footerOffset + 0x1b] = (byte) mValidPer;
			}

		}

	}

    return smspData;
}


public Message getOnMessageComplete() {
     Message onMessageComplete = mOnMessageComplete;
     mOnMessageComplete = null;
     return onMessageComplete;
}

public String getServiceCentreAddr() {
    String scAddr = "";
    if ( (mParamInd & 0xfd) == mParamInd ) {
        scAddr = mScAddr;
    }
    return scAddr;
}

public boolean isEmailGwTag() {
    Log.d("GSM", "AlphaTag : " + mAlphaTag );

    if ( mAlphaTag.length() > 0 ) {
        return (mAlphaTag.equalsIgnoreCase("EMAIL SETTINGS"));
    }
    return false;
}

public void setDestinationAddr(String number) throws InvalidParameterException {
    if ( number.length() > 20 ) {
        throw new InvalidParameterException( "Destination Address being set is greater than limit!" );
    }
    mDestAddr = number;
    mParamInd = (byte) (mParamInd & 0xfe);
}

public void setOnMessageComplete(Message onMessageComplete) {
    mOnMessageComplete = onMessageComplete;
}

public void setServiceCentreAddr(String number) throws InvalidParameterException {
    if ( number.length() > 20 ) {
        throw new InvalidParameterException( "Service Centre Address being set is greater than limit!" );
    }
    mScAddr = number;
    mParamInd = (byte) (mParamInd & 0xfd);    
}
}
