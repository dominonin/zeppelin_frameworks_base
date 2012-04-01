/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.gsm;

import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ALPHA;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_ISO_COUNTRY;
import static com.android.internal.telephony.TelephonyProperties.PROPERTY_ICC_OPERATOR_NUMERIC;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.AdnRecord;
import com.android.internal.telephony.AdnRecordCache;
import com.android.internal.telephony.AdnRecordLoader;
import com.android.internal.telephony.CommandsInterface;
import android.telephony.gsm.GsmCellLocation;
import com.android.internal.telephony.IccCardApplication;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccRecords;
import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.IccVmFixedException;
import com.android.internal.telephony.IccVmNotSupportedException;
import com.android.internal.telephony.MccTable;

import java.util.ArrayList;


/**
 * {@hide}
 */
public final class SIMRecords extends IccRecords {
    static final String LOG_TAG = "GSM";

    private static final boolean CRASH_RIL = false;

    private static final boolean DBG = true;

    // ***** Instance Variables

    VoiceMailConstants mVmConfig;


    SpnOverride mSpnOverride;

    // ***** Cached SIM State; cleared on channel close

    String imsi;
    boolean callForwardingEnabled;
    boolean isPlmnModeBitEnabled;
    boolean isPrevPlmnModeBitEnabled;

    /**
     * States only used by getSpnFsm FSM
     */
    private Get_Spn_Fsm_State spnState;

    /** CPHS service information (See CPHS 4.2 B.3.1.1)
     *  It will be set in onSimReady if reading GET_CPHS_INFO successfully
     *  mCphsInfo[0] is CPHS Phase
     *  mCphsInfo[1] and mCphsInfo[2] is CPHS Service Table
     */
    private byte[] mCphsInfo = null;

    byte[] efMWIS = null;
    byte[] efCPHS_MWI =null;
    byte[] mEfCff = null;
    byte[] mEfCfis = null;

    boolean isCspFileChanged;
    ArrayList oplCache;
    int oplDataLac1;
    int oplDataLac2;
    short oplDataPnnNum;
    boolean oplDataPresent;
    boolean oplFetchingException;
    boolean isNetworkManual;
    ArrayList pnnCache;
    String pnnDataLongName;
    boolean pnnDataPresent;
    String pnnDataShortName;
    boolean pnnFetchingException;
    int sstPlmnOplValue;
    
    int spnDisplayCondition;
    // Numeric network codes listed in TS 51.011 EF[SPDI]
    ArrayList<String> spdiNetworks = null;

    String pnnHomeName = null;

    // ***** Constants

    // Bitmasks for SPN display rules.
    static final int SPN_RULE_SHOW_SPN  = 0x01;
    static final int SPN_RULE_SHOW_PLMN = 0x02;

    // From TS 51.011 EF[SPDI] section
    static final int TAG_SPDI_PLMN_LIST = 0x80;

    // Full Name IEI from TS 24.008
    static final int TAG_FULL_NETWORK_NAME = 0x43;

    // Short Name IEI from TS 24.008
    static final int TAG_SHORT_NETWORK_NAME = 0x45;

    // active CFF from CPHS 4.2 B.4.5
    static final int CFF_UNCONDITIONAL_ACTIVE = 0x0a;
    static final int CFF_UNCONDITIONAL_DEACTIVE = 0x05;
    static final int CFF_LINE1_MASK = 0x0f;
    static final int CFF_LINE1_RESET = 0xf0;

    // CPHS Service Table (See CPHS 4.2 B.3.1)
    private static final int CPHS_SST_MBN_MASK = 0x30;
    private static final int CPHS_SST_MBN_ENABLED = 0x30;
    
    // USIM Stuff
    static final int EONS_ATT_ALG = 0x1;
    private static int EONS_DISABLED = 0;
    static final int EONS_TMO_ALG = 0x2;
    private static int ONLY_PNN_ENABLED = 0x2;
    private static int PNN_OPL_ENABLED = 0x1;
    private static int NOT_INITIALIZED = -1;

    // ***** Event Constants

    private static final int EVENT_SIM_READY = 1;
    private static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 2;
    private static final int EVENT_GET_IMSI_DONE = 3;
    private static final int EVENT_GET_ICCID_DONE = 4;
    private static final int EVENT_GET_MBI_DONE = 5;
    private static final int EVENT_GET_MBDN_DONE = 6;
    private static final int EVENT_GET_MWIS_DONE = 7;
    private static final int EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE = 8;
    private static final int EVENT_GET_AD_DONE = 9; // Admin data on SIM
    private static final int EVENT_GET_MSISDN_DONE = 10;
    private static final int EVENT_GET_CPHS_MAILBOX_DONE = 11;
    private static final int EVENT_GET_SPN_DONE = 12;
    private static final int EVENT_GET_SPDI_DONE = 13;
    private static final int EVENT_UPDATE_DONE = 14;
    private static final int EVENT_GET_PNN_DONE = 15;
    private static final int EVENT_GET_SST_DONE = 17;
    private static final int EVENT_GET_ALL_SMS_DONE = 18;
    private static final int EVENT_MARK_SMS_READ_DONE = 19;
    private static final int EVENT_SET_MBDN_DONE = 20;
    private static final int EVENT_SMS_ON_SIM = 21;
    private static final int EVENT_GET_SMS_DONE = 22;
    private static final int EVENT_GET_CFF_DONE = 24;
    private static final int EVENT_SET_CPHS_MAILBOX_DONE = 25;
    private static final int EVENT_GET_INFO_CPHS_DONE = 26;
    private static final int EVENT_SET_MSISDN_DONE = 30;
    private static final int EVENT_SIM_REFRESH = 31;
    private static final int EVENT_GET_CFIS_DONE = 32;
    private static final int EVENT_GET_ALL_OPL_RECORDS_DONE = 33;
    private static final int EVENT_GET_CSP_CPHS_DONE = 34;
    private static final int EVENT_GET_ALL_PNN_RECORDS_DONE = 35;
    private static final int EVENT_GET_EMAIL_GW_DONE = 36;
    private static final int EVENT_GET_USIM_ECC_DONE = 37;
    private static final int EVENT_GET_SIM_ECC_DONE = 38;
    private static final int EVENT_GET_CSP_DONE = 39;
    private static final int EVENT_AUTO_SELECT_DONE = 40;

    // ***** Constructor

    SIMRecords(GSMPhone p) {
        super(p);

        adnCache = new AdnRecordCache(phone);

        mVmConfig = new VoiceMailConstants();
        mSpnOverride = new SpnOverride();

        isCspFileChanged = false;
        isPlmnModeBitEnabled = true;
        isPrevPlmnModeBitEnabled = true;
        oplFetchingException = false;
        pnnFetchingException = false;
        isNetworkManual = false;
        
        recordsRequested = false;  // No load request is made till SIM ready

        // recordsToLoad is set to 0 because no requests are made yet
        recordsToLoad = 0;


        p.mCM.registerForSIMReady(this, EVENT_SIM_READY, null);
        p.mCM.registerForOffOrNotAvailable(
                        this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE, null);
        p.mCM.setOnSmsOnSim(this, EVENT_SMS_ON_SIM, null);
        p.mCM.setOnIccRefresh(this, EVENT_SIM_REFRESH, null);

        // Start off by setting empty state
        onRadioOffOrNotAvailable();

    }

    public void dispose() {
        //Unregister for all events
        phone.mCM.unregisterForSIMReady(this);
        phone.mCM.unregisterForOffOrNotAvailable( this);
        phone.mCM.unSetOnIccRefresh(this);
    }

    protected void finalize() {
        if(DBG) Log.d(LOG_TAG, "SIMRecords finalized");
    }

    protected void onRadioOffOrNotAvailable() {
        imsi = null;
        msisdn = null;
        voiceMailNum = null;
        countVoiceMessages = 0;
        mncLength = UNINITIALIZED;
        iccid = null;
        // -1 means no EF_SPN found; treat accordingly.
        spnDisplayCondition = -1;
        efMWIS = null;
        efCPHS_MWI = null;
        spdiNetworks = null;
        pnnHomeName = null;

        adnCache.reset();

        phone.setSystemProperty(PROPERTY_ICC_OPERATOR_NUMERIC, null);
        phone.setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, null);
        phone.setSystemProperty(PROPERTY_ICC_OPERATOR_ISO_COUNTRY, null);

        // recordsRequested is set to false indicating that the SIM
        // read requests made so far are not valid. This is set to
        // true only when fresh set of read requests are made.
        recordsRequested = false;
    }


    //***** Public Methods

    /** Returns null if SIM is not yet ready */
    public String getIMSI() {
        return imsi;
    }

    public String getMsisdnNumber() {
        return msisdn;
    }

    /**
     * Set subscriber number to SIM record
     *
     * The subscriber number is stored in EF_MSISDN (TS 51.011)
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (up to 10 characters)
     * @param number dailing nubmer (up to 20 digits)
     *        if the number starts with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public void setMsisdnNumber(String alphaTag, String number,
            Message onComplete) {

        msisdn = number;
        msisdnTag = alphaTag;

        if(DBG) log("Set MSISDN: " + msisdnTag +" " + msisdn);


        AdnRecord adn = new AdnRecord(msisdnTag, msisdn);

        new AdnRecordLoader(phone).updateEF(adn, EF_MSISDN, EF_EXT1, 1, null,
                obtainMessage(EVENT_SET_MSISDN_DONE, onComplete));
    }

    public String getMsisdnAlphaTag() {
        return msisdnTag;
    }

    public String getVoiceMailNumber() {
        return voiceMailNum;
    }

    /**
     * Set voice mail number to SIM record
     *
     * The voice mail number can be stored either in EF_MBDN (TS 51.011) or
     * EF_MAILBOX_CPHS (CPHS 4.2)
     *
     * If EF_MBDN is available, store the voice mail number to EF_MBDN
     *
     * If EF_MAILBOX_CPHS is enabled, store the voice mail number to EF_CHPS
     *
     * So the voice mail number will be stored in both EFs if both are available
     *
     * Return error only if both EF_MBDN and EF_MAILBOX_CPHS fail.
     *
     * When the operation is complete, onComplete will be sent to its handler
     *
     * @param alphaTag alpha-tagging of the dailing nubmer (upto 10 characters)
     * @param voiceNumber dailing nubmer (upto 20 digits)
     *        if the number is start with '+', then set to international TOA
     * @param onComplete
     *        onComplete.obj will be an AsyncResult
     *        ((AsyncResult)onComplete.obj).exception == null on success
     *        ((AsyncResult)onComplete.obj).exception != null on fail
     */
    public void setVoiceMailNumber(String alphaTag, String voiceNumber,
            Message onComplete) {
        if (isVoiceMailFixed) {
            AsyncResult.forMessage((onComplete)).exception =
                    new IccVmFixedException("Voicemail number is fixed by operator");
            onComplete.sendToTarget();
            return;
        }

        newVoiceMailNum = voiceNumber;
        newVoiceMailTag = alphaTag;

        AdnRecord adn = new AdnRecord(newVoiceMailTag, newVoiceMailNum);

        if (mailboxIndex != 0 && mailboxIndex != 0xff) {

            new AdnRecordLoader(phone).updateEF(adn, EF_MBDN, EF_EXT6,
                    mailboxIndex, null,
                    obtainMessage(EVENT_SET_MBDN_DONE, onComplete));

        } else if (isCphsMailboxEnabled()) {

            new AdnRecordLoader(phone).updateEF(adn, EF_MAILBOX_CPHS,
                    EF_EXT1, 1, null,
                    obtainMessage(EVENT_SET_CPHS_MAILBOX_DONE, onComplete));

        } else {
            AsyncResult.forMessage((onComplete)).exception =
                    new IccVmNotSupportedException("Update SIM voice mailbox error");
            onComplete.sendToTarget();
        }
    }

    public String getVoiceMailAlphaTag()
    {
        return voiceMailTag;
    }

    /**
     * Sets the SIM voice message waiting indicator records
     * @param line GSM Subscriber Profile Number, one-based. Only '1' is supported
     * @param countWaiting The number of messages waiting, if known. Use
     *                     -1 to indicate that an unknown number of
     *                      messages are waiting
     */
    public void
    setVoiceMessageWaiting(int line, int countWaiting) {
        if (line != 1) {
            // only profile 1 is supported
            return;
        }

        // range check
        if (countWaiting < 0) {
            countWaiting = -1;
        } else if (countWaiting > 0xff) {
            // TS 23.040 9.2.3.24.2
            // "The value 255 shall be taken to mean 255 or greater"
            countWaiting = 0xff;
        }

        countVoiceMessages = countWaiting;

        ((GSMPhone) phone).notifyMessageWaitingIndicator();

        try {
            if (efMWIS != null) {
                // TS 51.011 10.3.45

                // lsb of byte 0 is 'voicemail' status
                efMWIS[0] = (byte)((efMWIS[0] & 0xfe)
                                    | (countVoiceMessages == 0 ? 0 : 1));

                // byte 1 is the number of voice messages waiting
                if (countWaiting < 0) {
                    // The spec does not define what this should be
                    // if we don't know the count
                    efMWIS[1] = 0;
                } else {
                    efMWIS[1] = (byte) countWaiting;
                }

                phone.getIccFileHandler().updateEFLinearFixed(
                    EF_MWIS, 1, efMWIS, null,
                    obtainMessage (EVENT_UPDATE_DONE, EF_MWIS));
            }

            if (efCPHS_MWI != null) {
                    // Refer CPHS4_2.WW6 B4.2.3
                efCPHS_MWI[0] = (byte)((efCPHS_MWI[0] & 0xf0)
                            | (countVoiceMessages == 0 ? 0x5 : 0xa));

                phone.getIccFileHandler().updateEFTransparent(
                    EF_VOICE_MAIL_INDICATOR_CPHS, efCPHS_MWI,
                    obtainMessage (EVENT_UPDATE_DONE, EF_VOICE_MAIL_INDICATOR_CPHS));
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            Log.w(LOG_TAG,
                "Error saving voice mail state to SIM. Probably malformed SIM record", ex);
        }
    }

    public boolean getVoiceCallForwardingFlag() {
        return callForwardingEnabled;
    }

    public void setVoiceCallForwardingFlag(int line, boolean enable) {

        if (line != 1) return; // only line 1 is supported

        callForwardingEnabled = enable;

        ((GSMPhone) phone).notifyCallForwardingIndicator();

        try {
            if (mEfCfis != null) {
                // lsb is of byte 1 is voice status
                if (enable) {
                    mEfCfis[1] |= 1;
                } else {
                    mEfCfis[1] &= 0xfe;
                }

                // TODO: Should really update other fields in EF_CFIS, eg,
                // dialing number.  We don't read or use it right now.

                phone.getIccFileHandler().updateEFLinearFixed(
                        EF_CFIS, 1, mEfCfis, null,
                        obtainMessage (EVENT_UPDATE_DONE, EF_CFIS));
            }

            if (mEfCff != null) {
                if (enable) {
                    mEfCff[0] = (byte) ((mEfCff[0] & CFF_LINE1_RESET)
                            | CFF_UNCONDITIONAL_ACTIVE);
                } else {
                    mEfCff[0] = (byte) ((mEfCff[0] & CFF_LINE1_RESET)
                            | CFF_UNCONDITIONAL_DEACTIVE);
                }

                phone.getIccFileHandler().updateEFTransparent(
                        EF_CFF_CPHS, mEfCff,
                        obtainMessage (EVENT_UPDATE_DONE, EF_CFF_CPHS));
            }
        } catch (ArrayIndexOutOfBoundsException ex) {
            Log.w(LOG_TAG,
                    "Error saving call fowarding flag to SIM. "
                            + "Probably malformed SIM record", ex);

        }
    }

    /**
     * Called by STK Service when REFRESH is received.
     * @param fileChanged indicates whether any files changed
     * @param fileList if non-null, a list of EF files that changed
     */
    public void onRefresh(boolean fileChanged, int[] fileList) {
        if (fileChanged) {
            // A future optimization would be to inspect fileList and
            // only reload those files that we care about.  For now,
            // just re-fetch all SIM records that we cache.
            fetchSimRecords();
        }
    }

    /** Returns the 5 or 6 digit MCC/MNC of the operator that
     *  provided the SIM card. Returns null of SIM is not yet ready
     */
    String getSIMOperatorNumeric() {
        if (imsi == null || mncLength == UNINITIALIZED || mncLength == UNKNOWN) {
            return null;
        }

        // Length = length of MCC + length of MNC
        // length of mcc = 3 (TS 23.003 Section 2.2)
        return imsi.substring(0, 3 + mncLength);
    }

    // ***** Overridden from Handler
    public void handleMessage(Message msg) {
        AsyncResult ar;
        AdnRecord adn;

        byte data[];

        boolean isRecordLoadResponse = false;

        try { switch (msg.what) {
            case EVENT_SIM_READY:
                onSimReady();
            break;

            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                onRadioOffOrNotAvailable();
            break;

            /* IO events */
            case EVENT_GET_IMSI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.e(LOG_TAG, "Exception querying IMSI, Exception:" + ar.exception);
                    break;
                }

                imsi = (String) ar.result;

                // IMSI (MCC+MNC+MSIN) is at least 6 digits, but not more
                // than 15 (and usually 15).
                if (imsi != null && (imsi.length() < 6 || imsi.length() > 15)) {
                    Log.e(LOG_TAG, "invalid IMSI " + imsi);
                    imsi = null;
                }

                Log.d(LOG_TAG, "IMSI: " + imsi.substring(0, 6) + "xxxxxxxxx");

                if (mncLength == UNKNOWN) {
                    // the SIM has told us all it knows, but it didn't know the mnc length.
                    // guess using the mcc
                    try {
                        int mcc = Integer.parseInt(imsi.substring(0,3));
                        mncLength = MccTable.smallestDigitsMccForMnc(mcc);
                    } catch (NumberFormatException e) {
                        mncLength = UNKNOWN;
                        Log.e(LOG_TAG, "SIMRecords: Corrupt IMSI!");
                    }
                }

                if (mncLength != UNKNOWN && mncLength != UNINITIALIZED) {
                    // finally have both the imsi and the mncLength and can parse the imsi properly
                    MccTable.updateMccMncConfiguration(phone, imsi.substring(0, 3 + mncLength));
                }
                ((GSMPhone) phone).mSimCard.broadcastIccStateChangedIntent(
                        SimCard.INTENT_VALUE_ICC_IMSI, null);
            break;

            case EVENT_GET_MBI_DONE:
                boolean isValidMbdn;
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[]) ar.result;

                isValidMbdn = false;
                if (ar.exception == null) {
                    // Refer TS 51.011 Section 10.3.44 for content details
                    Log.d(LOG_TAG, "EF_MBI: " +
                            IccUtils.bytesToHexString(data));

                    // Voice mail record number stored first
                    mailboxIndex = (int)data[0] & 0xff;

                    // check if dailing numbe id valid
                    if (mailboxIndex != 0 && mailboxIndex != 0xff) {
                        Log.d(LOG_TAG, "Got valid mailbox number for MBDN");
                        isValidMbdn = true;
                    }
                }

                // one more record to load
                recordsToLoad += 1;

                if (isValidMbdn) {
                    // Note: MBDN was not included in NUM_OF_SIM_RECORDS_LOADED
                    new AdnRecordLoader(phone).loadFromEF(EF_MBDN, EF_EXT6,
                            mailboxIndex, obtainMessage(EVENT_GET_MBDN_DONE));
                } else {
                    // If this EF not present, try mailbox as in CPHS standard
                    // CPHS (CPHS4_2.WW6) is a european standard.
                    new AdnRecordLoader(phone).loadFromEF(EF_MAILBOX_CPHS,
                            EF_EXT1, 1,
                            obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                }

                break;
            case EVENT_GET_CPHS_MAILBOX_DONE:
            case EVENT_GET_MBDN_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {

                    Log.d(LOG_TAG, "Invalid or missing EF"
                        + ((msg.what == EVENT_GET_CPHS_MAILBOX_DONE) ? "[MAILBOX]" : "[MBDN]"));

                    // Bug #645770 fall back to CPHS
                    // FIXME should use SST to decide

                    if (msg.what == EVENT_GET_MBDN_DONE) {
                        //load CPHS on fail...
                        // FIXME right now, only load line1's CPHS voice mail entry

                        recordsToLoad += 1;
                        new AdnRecordLoader(phone).loadFromEF(
                                EF_MAILBOX_CPHS, EF_EXT1, 1,
                                obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                    }
                    break;
                }

                adn = (AdnRecord)ar.result;

                Log.d(LOG_TAG, "VM: " + adn +
                        ((msg.what == EVENT_GET_CPHS_MAILBOX_DONE) ? " EF[MAILBOX]" : " EF[MBDN]"));

                if (adn.isEmpty() && msg.what == EVENT_GET_MBDN_DONE) {
                    // Bug #645770 fall back to CPHS
                    // FIXME should use SST to decide
                    // FIXME right now, only load line1's CPHS voice mail entry
                    recordsToLoad += 1;
                    new AdnRecordLoader(phone).loadFromEF(
                            EF_MAILBOX_CPHS, EF_EXT1, 1,
                            obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));

                    break;
                }

                voiceMailNum = adn.getNumber();
                voiceMailTag = adn.getAlphaTag();
            break;

            case EVENT_GET_MSISDN_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    Log.d(LOG_TAG, "Invalid or missing EF[MSISDN]");
                    break;
                }

                adn = (AdnRecord)ar.result;

                msisdn = adn.getNumber();
                msisdnTag = adn.getAlphaTag();

                Log.d(LOG_TAG, "MSISDN: " + msisdn);
            break;

            case EVENT_SET_MSISDN_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;

                if (ar.userObj != null) {
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;

            case EVENT_GET_MWIS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                Log.d(LOG_TAG, "EF_MWIS: " +
                   IccUtils.bytesToHexString(data));

                efMWIS = data;

                if ((data[0] & 0xff) == 0xff) {
                    Log.d(LOG_TAG, "SIMRecords: Uninitialized record MWIS");
                    break;
                }

                // Refer TS 51.011 Section 10.3.45 for the content description
                boolean voiceMailWaiting = ((data[0] & 0x01) != 0);
                countVoiceMessages = data[1] & 0xff;

                if (voiceMailWaiting && countVoiceMessages == 0) {
                    // Unknown count = -1
                    countVoiceMessages = -1;
                }

                ((GSMPhone) phone).notifyMessageWaitingIndicator();
            break;

            case EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                efCPHS_MWI = data;

                // Use this data if the EF[MWIS] exists and
                // has been loaded

                if (efMWIS == null) {
                    int indicator = (int)(data[0] & 0xf);

                    // Refer CPHS4_2.WW6 B4.2.3
                    if (indicator == 0xA) {
                        // Unknown count = -1
                        countVoiceMessages = -1;
                    } else if (indicator == 0x5) {
                        countVoiceMessages = 0;
                    }

                    ((GSMPhone) phone).notifyMessageWaitingIndicator();
                }
            break;

            case EVENT_GET_ICCID_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                iccid = IccUtils.bcdToString(data, 0, data.length);

                Log.d(LOG_TAG, "iccid: " + iccid);

            break;


            case EVENT_GET_AD_DONE:
                try {
                    isRecordLoadResponse = true;

                    ar = (AsyncResult)msg.obj;
                    data = (byte[])ar.result;

                    if (ar.exception != null) {
                        break;
                    }

                    Log.d(LOG_TAG, "EF_AD: " +
                            IccUtils.bytesToHexString(data));

                    if (data.length < 3) {
                        Log.d(LOG_TAG, "SIMRecords: Corrupt AD data on SIM");
                        break;
                    }

                    if (data.length == 3) {
                        Log.d(LOG_TAG, "SIMRecords: MNC length not present in EF_AD");
                        break;
                    }

                    mncLength = (int)data[3] & 0xf;

                    if (mncLength == 0xf) {
                        mncLength = UNKNOWN;
                    }
                } finally {
                    if (mncLength == UNKNOWN || mncLength == UNINITIALIZED) {
                        if (imsi != null) {
                            try {
                                int mcc = Integer.parseInt(imsi.substring(0,3));

                                mncLength = MccTable.smallestDigitsMccForMnc(mcc);
                            } catch (NumberFormatException e) {
                                mncLength = UNKNOWN;
                                Log.e(LOG_TAG, "SIMRecords: Corrupt IMSI!");
                            }
                        } else {
                            // Indicate we got this info, but it didn't contain the length.
                            mncLength = UNKNOWN;

                            Log.d(LOG_TAG, "SIMRecords: MNC length not present in EF_AD");
                        }
                    }
                    if (imsi != null && mncLength != UNKNOWN) {
                        // finally have both imsi and the length of the mnc and can parse
                        // the imsi properly
                        MccTable.updateMccMncConfiguration(phone, imsi.substring(0, 3 + mncLength));
                    }
                }
            break;

            case EVENT_GET_SPN_DONE:
                isRecordLoadResponse = true;
                ar = (AsyncResult) msg.obj;
                getSpnFsm(false, ar);
            break;

            case EVENT_GET_CFF_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult) msg.obj;
                data = (byte[]) ar.result;

                if (ar.exception != null) {
                    break;
                }

                Log.d(LOG_TAG, "EF_CFF_CPHS: " +
                        IccUtils.bytesToHexString(data));
                mEfCff = data;

                if (mEfCfis == null) {
                    callForwardingEnabled =
                        ((data[0] & CFF_LINE1_MASK) == CFF_UNCONDITIONAL_ACTIVE);

                    ((GSMPhone) phone).notifyCallForwardingIndicator();
                }
                break;

            case EVENT_GET_SPDI_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                parseEfSpdi(data);
            break;

            case EVENT_UPDATE_DONE:
                ar = (AsyncResult)msg.obj;
                if (ar.exception != null) {
                    Log.i(LOG_TAG, "SIMRecords update failed", ar.exception);
                }
            break;

            case EVENT_GET_PNN_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                SimTlv tlv = new SimTlv(data, 0, data.length);

                for ( ; tlv.isValidObject() ; tlv.nextObject()) {
                    if (tlv.getTag() == TAG_FULL_NETWORK_NAME) {
                        pnnHomeName
                            = IccUtils.networkNameToString(
                                tlv.getData(), 0, tlv.getData().length);
                        break;
                    }
                }
            break;

            case EVENT_GET_ALL_SMS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                if (ar.exception != null)
                    break;

                handleSmses((ArrayList) ar.result);
                break;

            case EVENT_MARK_SMS_READ_DONE:
                Log.i("ENF", "marked read: sms " + msg.arg1);
                break;


            case EVENT_SMS_ON_SIM:
                isRecordLoadResponse = false;

                ar = (AsyncResult)msg.obj;

                int[] index = (int[])ar.result;

                if (ar.exception != null || index.length != 1) {
                    Log.e(LOG_TAG, "[SIMRecords] Error on SMS_ON_SIM with exp "
                            + ar.exception + " length " + index.length);
                } else {
                    Log.d(LOG_TAG, "READ EF_SMS RECORD index=" + index[0]);
                    phone.getIccFileHandler().loadEFLinearFixed(EF_SMS,index[0],
                            obtainMessage(EVENT_GET_SMS_DONE));
                }
                break;

            case EVENT_GET_SMS_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if (ar.exception == null) {
                    handleSms((byte[])ar.result);
                } else {
                    Log.e(LOG_TAG, "[SIMRecords] Error on GET_SMS with exp "
                            + ar.exception);
                }
                break;
            case EVENT_GET_SST_DONE:
                log( "EVENT_GET_SST_DONE");
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }
                if (DBG) log("EVENT_GET_SST_DONE - SST: " + IccUtils.bytesToHexString(data));
                handleSstData( data );
            break;

            case EVENT_GET_INFO_CPHS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                    break;
                }

                mCphsInfo = (byte[])ar.result;

                if (DBG) log("iCPHS: " + IccUtils.bytesToHexString(mCphsInfo));
            break;

            case EVENT_SET_MBDN_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;

                if (ar.exception == null) {
                    voiceMailNum = newVoiceMailNum;
                    voiceMailTag = newVoiceMailTag;
                }

                if (isCphsMailboxEnabled()) {
                    adn = new AdnRecord(voiceMailTag, voiceMailNum);
                    Message onCphsCompleted = (Message) ar.userObj;

                    /* write to cphs mailbox whenever it is available but
                    * we only need notify caller once if both updating are
                    * successful.
                    *
                    * so if set_mbdn successful, notify caller here and set
                    * onCphsCompleted to null
                    */
                    if (ar.exception == null && ar.userObj != null) {
                        AsyncResult.forMessage(((Message) ar.userObj)).exception
                                = null;
                        ((Message) ar.userObj).sendToTarget();

                        if (DBG) log("Callback with MBDN successful.");

                        onCphsCompleted = null;
                    }

                    new AdnRecordLoader(phone).
                            updateEF(adn, EF_MAILBOX_CPHS, EF_EXT1, 1, null,
                            obtainMessage(EVENT_SET_CPHS_MAILBOX_DONE,
                                    onCphsCompleted));
                } else {
                    if (ar.userObj != null) {
                        AsyncResult.forMessage(((Message) ar.userObj)).exception
                                = ar.exception;
                        ((Message) ar.userObj).sendToTarget();
                    }
                }
                break;
            case EVENT_SET_CPHS_MAILBOX_DONE:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
                if(ar.exception == null) {
                    voiceMailNum = newVoiceMailNum;
                    voiceMailTag = newVoiceMailTag;
                } else {
                    if (DBG) log("Set CPHS MailBox with exception: "
                            + ar.exception);
                }
                if (ar.userObj != null) {
                    if (DBG) log("Callback with CPHS MB successful.");
                    AsyncResult.forMessage(((Message) ar.userObj)).exception
                            = ar.exception;
                    ((Message) ar.userObj).sendToTarget();
                }
                break;
            case EVENT_SIM_REFRESH:
                isRecordLoadResponse = false;
                ar = (AsyncResult)msg.obj;
		if (DBG) log("Sim REFRESH with exception: " + ar.exception);
                if (ar.exception == null) {
                    handleSimRefresh((int[])(ar.result));
                }
                break;
            case EVENT_GET_CFIS_DONE:
                isRecordLoadResponse = true;

                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;

                if (ar.exception != null) {
                    break;
                }

                Log.d(LOG_TAG, "EF_CFIS: " +
                   IccUtils.bytesToHexString(data));

                mEfCfis = data;

                // Refer TS 51.011 Section 10.3.46 for the content description
                callForwardingEnabled = ((data[1] & 0x01) != 0);

                ((GSMPhone) phone).notifyCallForwardingIndicator();
                break;
            case EVENT_GET_ALL_OPL_RECORDS_DONE: //:pswitch_d26
            	isRecordLoadResponse = true;
            	if (DBG) log("EVENT_GET_ALL_OPL_RECORDS_DONE");

                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                	Log.e(LOG_TAG, "Exception in fetching OPL Records " + ar.exception);
                	oplCache = null;
                	oplFetchingException = true;
                    break;
                }
            	
                oplFetchingException = false;
                oplCache = (ArrayList) ar.result;
                displayEonsName( 0 );
                break;
            case EVENT_GET_ALL_PNN_RECORDS_DONE: //:pswitch_d83
            	isRecordLoadResponse = true;
            	if ( DBG ) log("EVENT_GET_ALL_PNN_RECORDS_DONE");
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                	Log.e(LOG_TAG, "Exception in fetching PNN Records " + ar.exception);
                	pnnCache = null;
                	pnnFetchingException = true;
                    break;
                }
                
                pnnFetchingException = false;
                pnnCache = (ArrayList) ar.result;
                displayEonsName(0);
                break;
            case EVENT_GET_EMAIL_GW_DONE: //:pswitch_35a
            	isRecordLoadResponse = true;
            	if (DBG) log("[handleMessage]: EVENT_GET_EMAIL_GW_DONE");
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                	if ( DBG ) log("[handleMessage]: Invalid or missing EF[SMSP]");
                    break;
                }
                if ( ar.result instanceof byte[] ) {
                	SmspRecord smspRec = new SmspRecord((byte[]) ar.result);
                	if ( smspRec.isEmailGwTag()) {
                		String emailGateway = smspRec.getServiceCentreAddr();
                		if ( DBG ) log("warning! it is  workaround for ATT:ignore email gateway number from simcard ");
                		if ( DBG ) log("[handleMessage]: emailGateway:" + emailGateway);
                	}
                	else {
                		if ( DBG ) log("not email gateway alpha tag");
                	}
                }
                else {
                	if ( DBG ) log("skip emailGateway ar.result=" + ar.result );
                }
                break;
            case EVENT_GET_USIM_ECC_DONE: //:pswitch_f61
            	isRecordLoadResponse = true;
            	StringBuffer eccList = new StringBuffer( "911,112" );
            	String carrier_ecc = SystemProperties.get("ro.mot.carrier.ecc");
            	if ( carrier_ecc != null ) {
            		eccList.append("," + carrier_ecc);
            		if ( DBG ) log("EVENT_GET_USIM_ECC_DONE Carrier ECC numbers added ECC list" + carrier_ecc);
            	}
            	String[] eccRecords;
                ar = (AsyncResult)msg.obj;

                if (ar.exception != null) {
                	if (DBG) log( "Either it is 2G SIM Card or something else happening..try to read emergency numbers from 2G Card now" );
                	phone.getIccFileHandler().loadEFTransparent(EF_ECC, obtainMessage(EVENT_GET_SIM_ECC_DONE));
                	recordsToLoad++;
                	break;
                }
                
                eccRecords = handleUsimEmergencyNumbers((ArrayList) ar.result);
                if ( eccRecords != null ) {
                	if ( DBG ) log("numECC records final coming out =" + eccRecords.length);
                	for (int iterator=0; iterator<eccRecords.length; iterator++) {
                		if ( eccRecords[iterator] == null )		continue;
                		if ( eccRecords[iterator] == "" )		continue;
                		if ( eccRecords[iterator] == "911" )	continue;
                		if ( eccRecords[iterator] == "112" )	continue;
                		boolean dup = false;
                		for (int j=0; j<iterator; j++) {
                			if ( !dup ) {
                				dup = eccRecords[iterator].equals( eccRecords[j]);
                			}
                		}
                		
                		if ( dup ) {
                			Log.i("SIMRecords.java", "Skipping dup ECC list number " + eccRecords[iterator]);
                		}
                		else {
                			eccList.append( "," + eccRecords[iterator]);
                			Log.i("SIMRecords.java", "Appending ECC list number " + eccRecords[iterator]);
                		}
                	}
                	if ( DBG ) log ("ECC new list in string " + eccList);
                }
            	SystemProperties.set("ro.ril.ecclist", eccList.toString());
            	break;
            case EVENT_GET_SIM_ECC_DONE: //:pswitch_10e0
            	isRecordLoadResponse = true;
            	StringBuffer eccSimList = new StringBuffer("911,112");
            	String[] eccSIMRecords = new String[6];
            	String sim_carrier_ecc = SystemProperties.get("ro.mot.carrier.ecc");
            	if (sim_carrier_ecc != null) {
            		eccSimList.append("," + sim_carrier_ecc);
            		if ( DBG ) log("EVENT_GET_SIM_ECC_DONE Carrier ECC numbers added to ECC list" + sim_carrier_ecc);
            	}
            	int count = 0;
            	int size = 0;
                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;
            	if ( DBG ) log ("response 2g sim emergency numbers: " + IccUtils.bytesToHexString( data ));
                if ((ar.exception != null) || (data.length < 1)) {
                	StringBuffer eccNoSimList = new StringBuffer("911,112,000,08,110,999,118,119");
                	SystemProperties.set("ro.ril.ecclist", eccNoSimList.toString());
                	if ( DBG ) log("No SIM card ECC new list in string " + eccNoSimList);
                }
                else {
                	size = (data.length / 3)*3;
                	
                	if ( size > 18 ) {
                		size = 18;
                	}
                	int iterator;
                	for (iterator = 0; iterator < size; iterator=iterator + 3) {
                		int j=iterator;
                		int digit = 0;
                		StringBuffer tempBuf = new StringBuffer();
            			while ( j < (iterator + 3)) {
            				if ( iterator >= size ) break;
            				digit = (data[iterator]>>0)&0xf;
            				if ( digit < 0 ) break;
            				if ( digit > 9 ) break;
            				tempBuf.append( digit );
            				j++;
                		}
            			eccSIMRecords[count++] = tempBuf.toString();
                	}
                	for (iterator = 0; iterator<count; iterator++) {
                		if ( eccSIMRecords[iterator]==null) break;
                		if ( eccSIMRecords[iterator].equals("")) break;
                		if ( eccSIMRecords[iterator].equals("911")) break;
                		if ( eccSIMRecords[iterator].equals("112")) break;
                		boolean dup = false;
                		for (int j=0; j<iterator; j++) {
                			if (!dup) {
                				dup = (eccSIMRecords[iterator].equals(eccSIMRecords[j]));
                			}
                		}
                		if ( dup ) {
                			Log.i("SIMRecords.java", "Skipping dup ECC list number " + eccSIMRecords[iterator]);
                		}
                		else {
                			eccSimList.append("," + eccSIMRecords[iterator]);
                			Log.i("SIMRecords.java", "Appending ECC list number " + eccSIMRecords[iterator]);	
                		}
                	}
                	SystemProperties.set("ro.ril.ecclist", eccSimList.toString());
                }
                break;
            case EVENT_GET_CSP_DONE: //:pswitch_e3b
            	isRecordLoadResponse = true;
                ar = (AsyncResult)msg.obj;
                data = (byte[])ar.result;
                
                if (DBG) log("EF_CSP: " + IccUtils.bytesToHexString(data));
                if ( ar.exception != null || data.length < 0 ) break;
                
            	int i;
            	for (i=0; i<data.length; i+=2) {
            		if ( (data[i] & 0xff) == 0xc0 ) {
            			break;
            		}
            	}
            	
                if (i<data.length) {
                	if ( (data[i+1] & 0x80) == 0x80 ) {
                		isPlmnModeBitEnabled = true;
                	}
                	else
                	{
                		isPlmnModeBitEnabled = false;
                	}
                }
                else {
                	Log.e(LOG_TAG, "Doesn\'t have a VAS field in CSP");
                }
                isNetworkManual = phone.getServiceState().getIsManualSelection();
                
                if ( isNetworkManual ) {
                	if (DBG) log("isCspChanged: " + isCspFileChanged + " isPlmnEnabled: " + isPlmnModeBitEnabled + " isPrevPlmnEnabled: " + isPrevPlmnModeBitEnabled);
                	
                	if ( (isCspFileChanged && ( isPlmnModeBitEnabled != isPrevPlmnModeBitEnabled )) 
                			|| ( (!isCspFileChanged) && (! isPlmnModeBitEnabled))) {
                		phone.setNetworkSelectionModeAutomatic(obtainMessage(EVENT_AUTO_SELECT_DONE));
                		isCspFileChanged = false;
                	}
                }
            	isPrevPlmnModeBitEnabled = isPlmnModeBitEnabled;
            	break;
            case EVENT_AUTO_SELECT_DONE: //:pswitch_f53 
                if ( DBG ) log ("RESET AUTOMATIC MODE DONE");
                break;

        }}catch (RuntimeException exc) {
            // I don't want these exceptions to be fatal
            Log.w(LOG_TAG, "Exception parsing SIM record", exc);
        } finally {
            // Count up record load responses even if they are fails
            if (isRecordLoadResponse) {
                onRecordLoaded();
            }
        }
    }

    private void handleFileUpdate(int efid) {
        switch(efid) {
            case EF_MBDN:
                recordsToLoad++;
                new AdnRecordLoader(phone).loadFromEF(EF_MBDN, EF_EXT6,
                        mailboxIndex, obtainMessage(EVENT_GET_MBDN_DONE));
                break;
            case EF_MAILBOX_CPHS:
                recordsToLoad++;
                new AdnRecordLoader(phone).loadFromEF(EF_MAILBOX_CPHS, EF_EXT1,
                        1, obtainMessage(EVENT_GET_CPHS_MAILBOX_DONE));
                break;
            case EF_CSP: //:sswitch_85
                recordsToLoad++;
                phone.getIccFileHandler().loadEFTransparent(EF_CSP, obtainMessage(EVENT_GET_CSP_DONE));
                isCspFileChanged = true;
            	break;
            case EF_OPL: //:sswitch_a0
            	int eonsAlg = getOnsAlg();
            	if (DBG) log( "EF_OPL - eonsAlg=" + eonsAlg );
            	if ( eonsAlg == 1 || eonsAlg == 2 ) {
            		if (DBG) log("SIM Refresh called for EF_OPL");
            		updateOplCache();
            	}
            	else {
            		if (DBG) log("handleFileUpdate() - ELSE EF_OPL");
                    fetchSimRecords();            		
            	}
            	break;
            case EF_PNN: //:sswitch_de
            	int eonsAlgPNN = getOnsAlg();
            	if (DBG) log("EF_PNN - eonsAlgPNN=" + eonsAlgPNN);
            	
            	if ( eonsAlgPNN == 1 || eonsAlgPNN == 2 ) {
            		if (DBG) log("SIM Refresh called for EF_PNN");
            		updatePnnCache();
            	}
            	else {
            		if (DBG) log("handleFileUpdate() - ELSE EF_PNN");
            		fetchSimRecords();
            	}
            	
            	break;
            default:
                // For now, fetch all records if this is not a
                // voicemail number.
                // TODO: Handle other cases, instead of fetching all.
                adnCache.reset();
                fetchSimRecords();
                break;
        }
    }

    private void handleSimRefresh(int[] result) {
        if (result == null || result.length == 0) {
	    if (DBG) log("handleSimRefresh without input");
            return;
        }

        switch ((result[0])) {
            case CommandsInterface.SIM_REFRESH_FILE_UPDATED:
 		if (DBG) log("handleSimRefresh with SIM_REFRESH_FILE_UPDATED");
                // result[1] contains the EFID of the updated file.
                int efid = result[1];
                handleFileUpdate(efid);
                break;
            case CommandsInterface.SIM_REFRESH_INIT:
		if (DBG) log("handleSimRefresh with SIM_REFRESH_INIT");
                // need to reload all files (that we care about)
                adnCache.reset();
                fetchSimRecords();
                break;
            case CommandsInterface.SIM_REFRESH_RESET:
		if (DBG) log("handleSimRefresh with SIM_REFRESH_RESET");
                phone.mCM.setRadioPower(false, null);
                /* Note: no need to call setRadioPower(true).  Assuming the desired
                * radio power state is still ON (as tracked by ServiceStateTracker),
                * ServiceStateTracker will call setRadioPower when it receives the
                * RADIO_STATE_CHANGED notification for the power off.  And if the
                * desired power state has changed in the interim, we don't want to
                * override it with an unconditional power on.
                */
                break;
            default:
                // unknown refresh operation
		if (DBG) log("handleSimRefresh with unknown operation");
                break;
        }
    }

    private void handleSms(byte[] ba) {
        if (ba[0] != 0)
            Log.d("ENF", "status : " + ba[0]);

        // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
        // 3 == "received by MS from network; message to be read"
        if (ba[0] == 3) {
            int n = ba.length;

            // Note: Data may include trailing FF's.  That's OK; message
            // should still parse correctly.
            byte[] pdu = new byte[n - 1];
            System.arraycopy(ba, 1, pdu, 0, n - 1);
            SmsMessage message = SmsMessage.createFromPdu(pdu);

            ((GSMPhone) phone).mSMS.dispatchMessage(message);
        }
    }


    private void handleSmses(ArrayList messages) {
        int count = messages.size();

        for (int i = 0; i < count; i++) {
            byte[] ba = (byte[]) messages.get(i);

            if (ba[0] != 0)
                Log.i("ENF", "status " + i + ": " + ba[0]);

            // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
            // 3 == "received by MS from network; message to be read"

            if (ba[0] == 3) {
                int n = ba.length;

                // Note: Data may include trailing FF's.  That's OK; message
                // should still parse correctly.
                byte[] pdu = new byte[n - 1];
                System.arraycopy(ba, 1, pdu, 0, n - 1);
                SmsMessage message = SmsMessage.createFromPdu(pdu);

                ((GSMPhone) phone).mSMS.dispatchMessage(message);

                // 3GPP TS 51.011 v5.0.0 (20011-12)  10.5.3
                // 1 == "received by MS from network; message read"

                ba[0] = 1;

                if (false) { // XXX writing seems to crash RdoServD
                    phone.getIccFileHandler().updateEFLinearFixed(EF_SMS,
                            i, ba, null, obtainMessage(EVENT_MARK_SMS_READ_DONE, i));
                }
            }
        }
    }

    protected void onRecordLoaded() {
        // One record loaded successfully or failed, In either case
        // we need to update the recordsToLoad count
        recordsToLoad -= 1;

        if (recordsToLoad == 0 && recordsRequested == true) {
            onAllRecordsLoaded();
        } else if (recordsToLoad < 0) {
            Log.e(LOG_TAG, "SIMRecords: recordsToLoad <0, programmer error suspected");
            recordsToLoad = 0;
        }
    }

    protected void onAllRecordsLoaded() {
        Log.d(LOG_TAG, "SIMRecords: record load complete");

        String operator = getSIMOperatorNumeric();

        // Some fields require more than one SIM record to set

        phone.setSystemProperty(PROPERTY_ICC_OPERATOR_NUMERIC, operator);

        if (imsi != null) {
            phone.setSystemProperty(PROPERTY_ICC_OPERATOR_ISO_COUNTRY,
                    MccTable.countryCodeForMcc(Integer.parseInt(imsi.substring(0,3))));
        }
        else {
            Log.e("SIM", "[SIMRecords] onAllRecordsLoaded: imsi is NULL!");
        }

        setVoiceMailByCountry(operator);
        setSpnFromConfig(operator);

        recordsLoadedRegistrants.notifyRegistrants(
            new AsyncResult(null, null, null));
        ((GSMPhone) phone).mSimCard.broadcastIccStateChangedIntent(
                SimCard.INTENT_VALUE_ICC_LOADED, null);
    }

    //***** Private methods

    private void setSpnFromConfig(String carrier) {
        if (mSpnOverride.containsCarrier(carrier)) {
            spn = mSpnOverride.getSpn(carrier);
        }
    }


    private void setVoiceMailByCountry (String spn) {
        if (mVmConfig.containsCarrier(spn)) {
            isVoiceMailFixed = true;
            voiceMailNum = mVmConfig.getVoiceMailNumber(spn);
            voiceMailTag = mVmConfig.getVoiceMailTag(spn);
        }
    }

    private void onSimReady() {
        /* broadcast intent SIM_READY here so that we can make sure
          READY is sent before IMSI ready
        */
        ((GSMPhone) phone).mSimCard.broadcastIccStateChangedIntent(
                SimCard.INTENT_VALUE_ICC_READY, null);

        fetchSimRecords();
    }

    private void fetchSimRecords() {
        recordsRequested = true;
        IccFileHandler iccFh = phone.getIccFileHandler();

        Log.v(LOG_TAG, "SIMRecords:fetchSimRecords " + recordsToLoad);

        phone.mCM.getIMSI(obtainMessage(EVENT_GET_IMSI_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_ICCID, obtainMessage(EVENT_GET_ICCID_DONE));
        recordsToLoad++;

        // FIXME should examine EF[MSISDN]'s capability configuration
        // to determine which is the voice/data/fax line
        new AdnRecordLoader(phone).loadFromEF(EF_MSISDN, EF_EXT1, 1,
                    obtainMessage(EVENT_GET_MSISDN_DONE));
        recordsToLoad++;

        // Record number is subscriber profile
        iccFh.loadEFLinearFixed(EF_MBI, 1, obtainMessage(EVENT_GET_MBI_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_AD, obtainMessage(EVENT_GET_AD_DONE));
        recordsToLoad++;

        // Record number is subscriber profile
        iccFh.loadEFLinearFixed(EF_MWIS, 1, obtainMessage(EVENT_GET_MWIS_DONE));
        recordsToLoad++;


        // Also load CPHS-style voice mail indicator, which stores
        // the same info as EF[MWIS]. If both exist, both are updated
        // but the EF[MWIS] data is preferred
        // Please note this must be loaded after EF[MWIS]
        iccFh.loadEFTransparent(
                EF_VOICE_MAIL_INDICATOR_CPHS,
                obtainMessage(EVENT_GET_VOICE_MAIL_INDICATOR_CPHS_DONE));
        recordsToLoad++;

        // Same goes for Call Forward Status indicator: fetch both
        // EF[CFIS] and CPHS-EF, with EF[CFIS] preferred.
        iccFh.loadEFLinearFixed(EF_CFIS, 1, obtainMessage(EVENT_GET_CFIS_DONE));
        recordsToLoad++;
        iccFh.loadEFTransparent(EF_CFF_CPHS, obtainMessage(EVENT_GET_CFF_DONE));
        recordsToLoad++;
        
        getSpnFsm(true, null);

        iccFh.loadEFTransparent(EF_SPDI, obtainMessage(EVENT_GET_SPDI_DONE));
        recordsToLoad++;
        
        int eonsAlg = getOnsAlg();
        
        if (DBG) log("eonsAlg=" + eonsAlg);
        
        if ( eonsAlg != EONS_ATT_ALG && eonsAlg != EONS_TMO_ALG) {
            iccFh.loadEFLinearFixed(EF_PNN, 1, obtainMessage(EVENT_GET_PNN_DONE));
            recordsToLoad++;        	
        }

        iccFh.loadEFTransparent(EF_INFO_CPHS, obtainMessage(EVENT_GET_INFO_CPHS_DONE));
        recordsToLoad++;

        iccFh.loadEFTransparent(EF_CSP_CPHS, obtainMessage(EVENT_GET_CSP_DONE));
        recordsToLoad++;

        iccFh.loadEFLinearFixedAll(EF_ECC, obtainMessage(EVENT_GET_USIM_ECC_DONE));
        recordsToLoad++;
        
        updateOplCache();
        updatePnnCache();
        
        iccFh.loadEFTransparent(EF_SST, obtainMessage(EVENT_GET_SST_DONE));
        recordsToLoad++;

        if ( SystemProperties.getBoolean("persist.cust.tel.adapt", false) || SystemProperties.getBoolean("persist.cust.tel.efcsp.plmn", false)) {
        	if (DBG) log("Request EF_CSP_CPHS");
        	
            iccFh.loadEFTransparent(EF_CSP_CPHS, obtainMessage(EVENT_GET_CSP_CPHS_DONE));
            recordsToLoad++;
        }

        iccFh.loadEFLinearFixed(EF_SMSP, 2, obtainMessage(EVENT_GET_EMAIL_GW_DONE));
        recordsToLoad++;
        
        
        // XXX should seek instead of examining them all
//        if (false) { // XXX
//            iccFh.loadEFLinearFixedAll(EF_SMS, obtainMessage(EVENT_GET_ALL_SMS_DONE));
//            recordsToLoad++;
//        }

//        if (CRASH_RIL) {
//            String sms = "0107912160130310f20404d0110041007030208054832b0120"
//                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
//                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
//                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
//                         + "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
//                         + "ffffffffffffffffffffffffffffff";
//            byte[] ba = IccUtils.hexStringToBytes(sms);

//            iccFh.updateEFLinearFixed(EF_SMS, 1, ba, null,
//                            obtainMessage(EVENT_MARK_SMS_READ_DONE, 1));
//        }
    }

    /**
     * Returns the SpnDisplayRule based on settings on the SIM and the
     * specified plmn (currently-registered PLMN).  See TS 22.101 Annex A
     * and TS 51.011 10.3.11 for details.
     *
     * If the SPN is not found on the SIM, the rule is always PLMN_ONLY.
     */
    protected int getDisplayRule(String plmn) {
        int rule;
        if (spn == null || spnDisplayCondition == -1) {
            // EF_SPN was not found on the SIM, or not yet loaded.  Just show ONS.
            rule = SPN_RULE_SHOW_PLMN;
        } else if (isOnMatchingPlmn(plmn)) {
            rule = SPN_RULE_SHOW_SPN;
            if ((spnDisplayCondition & 0x01) == 0x01) {
                // ONS required when registered to HPLMN or PLMN in EF_SPDI
                rule |= SPN_RULE_SHOW_PLMN;
            }
        } else {
            rule = SPN_RULE_SHOW_PLMN;
            if ((spnDisplayCondition & 0x02) == 0x00) {
                // SPN required if not registered to HPLMN or PLMN in EF_SPDI
                rule |= SPN_RULE_SHOW_SPN;
            }
        }
        return rule;
    }

    /**
     * Checks if plmn is HPLMN or on the spdiNetworks list.
     */
    private boolean isOnMatchingPlmn(String plmn) {
        if (plmn == null) return false;

        if (plmn.equals(getSIMOperatorNumeric())) {
            return true;
        }

        if (spdiNetworks != null) {
            for (String spdiNet : spdiNetworks) {
                if (plmn.equals(spdiNet)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * States of Get SPN Finite State Machine which only used by getSpnFsm()
     */
    private enum Get_Spn_Fsm_State {
        IDLE,               // No initialized
        INIT,               // Start FSM
        READ_SPN_3GPP,      // Load EF_SPN firstly
        READ_SPN_CPHS,      // Load EF_SPN_CPHS secondly
        READ_SPN_SHORT_CPHS // Load EF_SPN_SHORT_CPHS last
    }

    /**
     * Finite State Machine to load Service Provider Name , which can be stored
     * in either EF_SPN (3GPP), EF_SPN_CPHS, or EF_SPN_SHORT_CPHS (CPHS4.2)
     *
     * After starting, FSM will search SPN EFs in order and stop after finding
     * the first valid SPN
     *
     * @param start set true only for initialize loading
     * @param ar the AsyncResult from loadEFTransparent
     *        ar.exception holds exception in error
     *        ar.result is byte[] for data in success
     */
    private void getSpnFsm(boolean start, AsyncResult ar) {
        byte[] data;

        if (start) {
            spnState = Get_Spn_Fsm_State.INIT;
        }

        switch(spnState){
            case INIT:
                spn = null;

                phone.getIccFileHandler().loadEFTransparent( EF_SPN,
                        obtainMessage(EVENT_GET_SPN_DONE));
                recordsToLoad++;

                spnState = Get_Spn_Fsm_State.READ_SPN_3GPP;
                break;
            case READ_SPN_3GPP:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    spnDisplayCondition = 0xff & data[0];
                    spn = IccUtils.adnStringFieldToString(data, 1, data.length - 1);

                    if (DBG) log("Load EF_SPN: " + spn
                            + " spnDisplayCondition: " + spnDisplayCondition);
                    phone.setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, spn);

                    spnState = Get_Spn_Fsm_State.IDLE;
                } else {
                    phone.getIccFileHandler().loadEFTransparent( EF_SPN_CPHS,
                            obtainMessage(EVENT_GET_SPN_DONE));
                    recordsToLoad++;

                    spnState = Get_Spn_Fsm_State.READ_SPN_CPHS;

                    // See TS 51.011 10.3.11.  Basically, default to
                    // show PLMN always, and SPN also if roaming.
                    spnDisplayCondition = -1;
                }
                break;
            case READ_SPN_CPHS:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    spn = IccUtils.adnStringFieldToString(
                            data, 0, data.length - 1 );

                    if (DBG) log("Load EF_SPN_CPHS: " + spn);
                    phone.setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, spn);

                    spnState = Get_Spn_Fsm_State.IDLE;
                } else {
                    phone.getIccFileHandler().loadEFTransparent(
                            EF_SPN_SHORT_CPHS, obtainMessage(EVENT_GET_SPN_DONE));
                    recordsToLoad++;

                    spnState = Get_Spn_Fsm_State.READ_SPN_SHORT_CPHS;
                }
                break;
            case READ_SPN_SHORT_CPHS:
                if (ar != null && ar.exception == null) {
                    data = (byte[]) ar.result;
                    spn = IccUtils.adnStringFieldToString(
                            data, 0, data.length - 1);

                    if (DBG) log("Load EF_SPN_SHORT_CPHS: " + spn);
                    phone.setSystemProperty(PROPERTY_ICC_OPERATOR_ALPHA, spn);
                }else {
                    if (DBG) log("No SPN loaded in either CHPS or 3GPP");
                }

                spnState = Get_Spn_Fsm_State.IDLE;
                break;
            default:
                spnState = Get_Spn_Fsm_State.IDLE;
        }
    }

    /**
     * Parse TS 51.011 EF[SPDI] record
     * This record contains the list of numeric network IDs that
     * are treated specially when determining SPN display
     */
    private void
    parseEfSpdi(byte[] data) {
        SimTlv tlv = new SimTlv(data, 0, data.length);

        byte[] plmnEntries = null;

        // There should only be one TAG_SPDI_PLMN_LIST
        for ( ; tlv.isValidObject() ; tlv.nextObject()) {
            if (tlv.getTag() == TAG_SPDI_PLMN_LIST) {
                plmnEntries = tlv.getData();
                break;
            }
        }

        if (plmnEntries == null) {
            return;
        }

        spdiNetworks = new ArrayList<String>(plmnEntries.length / 3);

        for (int i = 0 ; i + 2 < plmnEntries.length ; i += 3) {
            String plmnCode;
            plmnCode = IccUtils.bcdToString(plmnEntries, i, 3);

            // Valid operator codes are 5 or 6 digits
            if (plmnCode.length() >= 5) {
                log("EF_SPDI network: " + plmnCode);
                spdiNetworks.add(plmnCode);
            }
        }
    }

    /**
     * check to see if Mailbox Number is allocated and activated in CPHS SST
     */
    private boolean isCphsMailboxEnabled() {
        if (mCphsInfo == null)  return false;
        return ((mCphsInfo[1] & CPHS_SST_MBN_MASK) == CPHS_SST_MBN_ENABLED );
    }

    protected void log(String s) {
        Log.d(LOG_TAG, "[SIMRecords] " + s);
    }

    private void handleSstData( byte[] data ) {
	if ( DBG ) log ("handleSstData()");
	
	if ( phone.getIccCard().isApplicationOnIcc( IccCardApplication.AppType.APPTYPE_USIM)) {
		int pinDisable = data[0] & 3;
		if ( DBG ) log( "CHV1 disable setting is: " + Integer.toString( pinDisable, 16 ));
		
		if ( pinDisable == 0 ) {
			phone.getIccCard().setSSTPinDisableAllow(true);			
		}
		else {
			phone.getIccCard().setSSTPinDisableAllow(false);
		}
	}
	try {
		String eonsValue = SystemProperties.get("persist.cust.tel.eons");
		if ( eonsValue != null && Integer.valueOf( eonsValue ).intValue()==EONS_TMO_ALG) {
			if ( DBG ) log("handleSstData() - EONS enabled for T-Mobile.");
			sstPlmnOplValue = EONS_DISABLED;
			if ( (!oplFetchingException) && (!pnnFetchingException) ) {
				if ( DBG ) log("handleSstData() - No PNN/OPL fetching exceptions.");
				sstPlmnOplValue = PNN_OPL_ENABLED;
			}
			if ( DBG ) log("handleSstData() - sstPlmnOplValue = " + sstPlmnOplValue);
		}
		else {
			if ( SystemProperties.getBoolean("persist.cust.tel.simtype", false)) {
				sstPlmnOplValue = (data[12]>>4)&15;
				if ( sstPlmnOplValue == 15 ) {
					sstPlmnOplValue = PNN_OPL_ENABLED;
					if ( DBG ) log("SST: 2G Sim,PNN and OPL services enabled " + sstPlmnOplValue );
				}
				else {
					//cond_109
					if ( sstPlmnOplValue == 3 ) {
						sstPlmnOplValue = ONLY_PNN_ENABLED;
						if ( DBG ) log("SST: 2G Sim,PNN enabled, OPL disabled " + sstPlmnOplValue);
					}
					else {
						sstPlmnOplValue = EONS_DISABLED;
						if ( DBG ) log("SST: 2G Sim,PNN disabled, disabling EONS " + sstPlmnOplValue);
					}
				}
			}
			else {
				//cond_151
				sstPlmnOplValue = (data[5]>>4)&3;
				if ( sstPlmnOplValue == 0 ) {
					sstPlmnOplValue = PNN_OPL_ENABLED;
					if ( DBG ) log("SST: 3G Sim,PNN and OPL services enabled " + sstPlmnOplValue);
				}
				else {
					// cond_180
					if (sstPlmnOplValue == 1) {
						sstPlmnOplValue = ONLY_PNN_ENABLED;
						if ( DBG ) log("SST: 3G Sim,PNN enabled, OPL disabled " + sstPlmnOplValue);
					}
					else {
						// cond_1a6
						sstPlmnOplValue = EONS_DISABLED;
						if ( DBG ) log("SST: 3G Sim,PNN disabled, disabling EONS " + sstPlmnOplValue);
					}
				}
				
			}
		}
		if ( sstPlmnOplValue == EONS_DISABLED) {
			((GSMPhone) phone).mSST.updateSpnDisplayWrapper();
		}
	}
	catch(Exception e) {
		Log.e( "SIMRecords", "Exception in processing SST Data " + e);
	}
}

private static String[] handleUsimEmergencyNumbers(ArrayList messages) {
	if ( messages == null ) {
		return null;
	}
	int numEccRecords=messages.size();
	String[] tempEccRecords = new String[numEccRecords];
	if ( DBG ) Log.d( LOG_TAG, "handleUsimEmergencyNumbers " + messages + " message size=" + numEccRecords);
	for ( int i=0; i<numEccRecords; i++ ) {
		byte[] ba = (byte[]) messages.get(i);
		tempEccRecords[i] = extractEmergencyNumber( ba );
	}
	return tempEccRecords;
}

void updateOplCache() {
	if ( DBG ) log("updateOplCache()");
	IccFileHandler iccFh = phone.getIccFileHandler();
	
    iccFh.loadEFLinearFixedAll(EF_OPL, obtainMessage(EVENT_GET_ALL_OPL_RECORDS_DONE));
    recordsToLoad++;
}

void updatePnnCache() {
	if ( DBG ) log( "updatePnnCache()");
	IccFileHandler iccFh = phone.getIccFileHandler();
    iccFh.loadEFLinearFixedAll(EF_PNN, obtainMessage(EVENT_GET_ALL_PNN_RECORDS_DONE));
}

private void displayEonsName(int flag) {
	int[] simPlmn = new int[6]; //v14
	int[] bcchPlmn = new int[6];//v4
	int count = 0;
	int bcchPlmnLength = 0;//v5
	if ( oplCache == null ) {
		if ( DBG ) log("oplCache is null");
		return;
	}
	
	count = oplCache.size();
	
	if ( DBG ) log("flag=" + flag);
	
	String regOperator = ( flag == 1 ) ? ((GSMPhone) phone).mSST.newSS.getOperatorNumeric() : ((GSMPhone) phone).mSST.ss.getOperatorNumeric();
	if ( regOperator == null || regOperator.trim().length() == 0 ) {
		if ( DBG ) log("Registered operator is null or empty.");
		useMEName();
		return;
	}
	
	if ( DBG ) log("Number of OPL records = " + count + " regOperator = " + regOperator);
	
	oplDataPresent = true;
	int hLac = -1;
	GsmCellLocation loc = (GsmCellLocation) phone.getCellLocation();
	if ( loc != null ) {
		hLac = loc.getLac();
	}
	if ( DBG ) log("hLac="+hLac);
	if ( hLac == -1 ) {
		log("Registered Lac is -1.");
		return;
	}
	int ind;
	for ( ind=0; ind < count; ind++ ) {
		try {
			byte[] data = (byte[]) oplCache.get(ind);
			simPlmn[0]=data[0]&0xf;
			simPlmn[1]=(data[0]>>4)&0xf;
			simPlmn[2]=data[1]&0xf;
			simPlmn[3]=data[2]&0xf;
			simPlmn[4]=(data[2]>>4)&0xf;
			simPlmn[5]=(data[1]>>4)&0xf;
			bcchPlmnLength = regOperator.length();
			for (int ind1=0; ind1<bcchPlmnLength; ind1++) {
				bcchPlmn[ind1]=(regOperator.charAt(ind1) - 0x30);
			}
			oplDataLac1 = ((data[3] & 0xff) << 8) | (data[4] & 0xff);
			oplDataLac2 = ((data[5] & 0xff) << 8) | (data[6] & 0xff);
			oplDataPnnNum = (short) (data[7]&0xff);
			if (DBG) log("lac1=" + oplDataLac1 + " lac2=" + oplDataLac2 + " hLac=" + hLac + " pnn rec=" + oplDataPnnNum );
			if (matchSimPlmn( simPlmn, bcchPlmn, bcchPlmnLength)) {
				if ( oplDataLac1 > hLac || hLac > oplDataLac2 ) {
					oplDataPresent = false;
					Log.w(LOG_TAG, "HLAC is not with in range of EF_OPL\'s LACs,ignoring pnn data, hLac=" + hLac + " lac1=" + oplDataLac1 + " lac2=" + oplDataLac2);
					continue;
				}
				if ( oplDataPnnNum < 0 || oplDataPnnNum > 0xff ) {
					oplDataPresent = false;
					Log.w(LOG_TAG, "PNN record number in EF_OPL is not valid");
					continue;
				}
				if ( DBG ) log( " lac1=" + oplDataLac1 + " lac2=" + oplDataLac2 + " hLac=" + hLac + " pnn rec=" + oplDataPnnNum);
				getNameFromPnnRecord(oplDataPnnNum);
			}
			else {
				oplDataPresent = false;
				if ( DBG ) log("plmn in EF_OPL does not match reg plmn,ignoring pnn data sim plmn " + simPlmn[0]);
			}
		}
		catch(Exception e) {
			Log.e(LOG_TAG, "Exception while processing OPL data " + e);
		}
	}
	if ( ind >= count ) {
		if ( DBG ) log("No matching OPL record found, using default method");
		useMEName();
	}
}

private static String extractEmergencyNumber(byte[] ba) {
	int digit = 0;
	StringBuffer tempbuf = new StringBuffer();
	
	if ( ba != null ) {
		for ( int j=0; j<3; j++) {
			digit = (ba[j] >> 0) & 0xf;
			if (digit < 0 || digit > 9)
				break;
			tempbuf.append(digit);
		}
	}
	
	return tempbuf.toString();
	
}

private void getNameFromPnnRecord( int record ) {
	int length = 0;
	
	if ( pnnCache == null || record > pnnCache.size() || record < 1 ) {
		if ( DBG ) log("pnnCache is null/Invalid PNN Rec, using default method");
		useMEName();
		return;
	}
	
	if ( DBG ) log("Number of PNN records = " + pnnCache.size());
	
	try {
		byte[] data = (byte[]) pnnCache.get(record - 1);
		if (DBG) log("PNN Record Number " + record + " ,hex data " + IccUtils.bytesToHexString( data ));
		pnnDataPresent = true;
		
		if ( data[0] == -1 || data[1] == -1 ) {
			pnnDataPresent = false;
			Log.e(LOG_TAG, "EF_PNN: Invalid EF_PNN Data");
		}
		else {
			length = data[1];
			if (DBG) log("PNN longname length " + length);
			pnnDataLongName = IccUtils.networkNameToString( data, 2, length);
			if (DBG) log("PNN longname : " + pnnDataLongName);
			
			if ((data[length + 2] == -1) || (data[length+3] == -1)) {
				if (DBG) log("No PNN shortname");
			}
			else {
				if (DBG) log("PNN shortname length : " + data[length+3]);
				pnnDataShortName = IccUtils.networkNameToString(data, length + 4, data[length+3]);
				if (DBG) log("PNN Shortname : " + pnnDataShortName);				
			}	
		}
	}
	catch(Exception e) {
		Log.e(LOG_TAG, "Exception while processing PNN data " + e);
	}
	((GSMPhone) phone).mSST.updateSpnDisplayWrapper();
}


int getOnsAlg() {
	int ons_alg = 0;
	String eons_prop = SystemProperties.get( "persist.cust.tel.eons" );
	String logMessage = null;
	
	if ( DBG ) log("Checking if EONS is enabled.");
	if ( DBG ) log("sstPlmnOplValue=" + sstPlmnOplValue);
	if( sstPlmnOplValue == EONS_DISABLED || sstPlmnOplValue == NOT_INITIALIZED ) {
		if ( DBG ) log("EONS is disabled because of SST restriction.");
		return 0;
	}
	else {
		if ( adaptPropSet() ) {
			logMessage = "EONS algorithm enabled because adapt property is set.";
			ons_alg = 1;
		}
		else {
			if ( eons_prop != null && eons_prop.length() > 0) {
				try {
					if ( Integer.valueOf( eons_prop ).intValue() == 1 ) {
						ons_alg = 1;
						logMessage = "EONS algorithm enabled for AT&T.";
					}
					if ( Integer.valueOf( eons_prop ).intValue() == 2 ) {
						ons_alg = 2;
						logMessage = "EONS algorithm enabled for T-Mobile.";
					}
				}
				catch(Exception e) {
					Log.e(LOG_TAG, "Exception on reading persist.cust.tel.eons " + e);
				}
			}
		}
		
		if ( logMessage != null ) {
			Log.i(LOG_TAG, logMessage);
		}
	}
	return ons_alg;
}

String getPnnLongName() {
	if ( isTelefonicaHomeOperator() ) {
		return pnnHomeName;
	}
	if ( pnnDataPresent ) {
		SystemProperties.set("gsm.eons.name", pnnDataLongName);
		Log.i(LOG_TAG, "Property gsm.eons.name set to " + SystemProperties.get("gsm.eons.name"));
		return pnnDataLongName;
	}
	
	SystemProperties.set("gsm.eons.name", null);
	Log.i(LOG_TAG, "Property gsm.eons.name set to null");
	return null;
}

public boolean getReadPlmnModeFlag() {
	return isPlmnModeBitEnabled;
}

boolean adaptPropSet() {
	boolean adapt_set = false;
	String adapt_prop = SystemProperties.get("persist.cust.tel.adapt");
	if ( DBG ) log( "adapt_prop=" + adapt_prop);
	
	if ( adapt_prop != null && adapt_prop.length() > 0) {
		try {
			if ( Integer.valueOf(adapt_prop).intValue() == 1 ) {
				adapt_set = true;
				if ( DBG ) log("adapt property enabled.");
			}			
		}
		catch(Exception e) {
			Log.e(LOG_TAG, "Exception on reading persist.cust.tel.adapt " + e );
		}
	}
	
	return adapt_set;	
}

boolean isTelefonicaHomeOperator() {
	String home_operator = SystemProperties.get("gsm.sim.operator.numeric");
	
	if ( home_operator == null ) {
		return false;
	}
	
	if ( home_operator.equals("21405")) {
		return true;
	}
	
	if ( home_operator.equals(0x343f5)) {
		return true;
	}
	
	return false;
}

void useMEName() {
	oplDataPresent = false;
	pnnDataPresent = false;
	((GSMPhone)phone).mSST.updateSpnDisplayWrapper();
}

boolean matchSimPlmn(int[] simPlmn, int[] bcchPlmn, int length) {
	int wildCardDigit = 0xd;
	boolean match = false;
	
	if ( simPlmn[5] == 0xf ) {
		simPlmn[5] = 0;
	}
	
	for ( int i=0; i<length; i++) {
		if ( simPlmn[i] == wildCardDigit ) {
			simPlmn[i] = bcchPlmn[i];
		}
	}
	
	if ( (simPlmn[0] == bcchPlmn[0]) && (simPlmn[1] == bcchPlmn[1]) && (simPlmn[2] == bcchPlmn[2])) {
		if ( length == 5 ) {
			if ( (simPlmn[3] == bcchPlmn[3]) && (simPlmn[4] == bcchPlmn[4]) ) {
				match = true;
			} else {
				match = false;
			}
		}
		else {
			if ( (simPlmn[3] == bcchPlmn[3]) && (simPlmn[4] == bcchPlmn[4]) && (simPlmn[5] == bcchPlmn[5]))  {
				match = true;
			} else {
				match = false;
			}			
		}
	}
	
	return match;
}


}
