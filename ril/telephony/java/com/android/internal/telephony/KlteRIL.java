/*
 * Copyright (c) 2014, The CyanogenMod Project. All rights reserved.
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

package com.android.internal.telephony;

import static com.android.internal.telephony.RILConstants.*;

import android.content.Context;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.telephony.SmsMessage;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.telephony.Rlog;

import android.telephony.SignalStrength;

import android.telephony.PhoneNumberUtils;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;
import com.android.internal.telephony.cdma.CdmaInformationRecords;
import com.android.internal.telephony.cdma.CdmaInformationRecords.CdmaSignalInfoRec;
import com.android.internal.telephony.cdma.SignalToneUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import com.android.internal.telephony.uicc.IccCardApplicationStatus;
import com.android.internal.telephony.uicc.IccCardStatus;

/**
 * RIL customization for Galaxy S5 LTE devices
 *
 * {@hide}
 */
public class KlteRIL extends RIL implements CommandsInterface {

    private AudioManager mAudioManager;

    private Object mSMSLock = new Object();
    private boolean mIsSendingSMS = false;
    protected boolean isGSM = false;
    public static final long SEND_SMS_TIMEOUT_IN_MS = 30000;
    private boolean newril = needsOldRilFeature("newril"); //4.4.4 verson of Samsung RIL

    private Message mPendingGetSimStatus;

    public KlteRIL(Context context, int preferredNetworkType,
            int cdmaSubscription, Integer instanceId) {
        super(context, preferredNetworkType, cdmaSubscription);
        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        mQANElements = 6;
    }

    public KlteRIL(Context context, int networkMode,
            int cdmaSubscription) {
        super(context, networkMode, cdmaSubscription);
        mAudioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        mQANElements = 6;
    }

    @Override
    public void
    dial(String address, int clirMode, UUSInfo uusInfo, Message result) {
        if (PhoneNumberUtils.isEmergencyNumber(address)) {
            dialEmergencyCall(address, clirMode, result);
            return;
        }

        RILRequest rr = RILRequest.obtain(RIL_REQUEST_DIAL, result);

        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);     // CallDetails.call_type
        rr.mParcel.writeInt(1);     // CallDetails.call_domain
        rr.mParcel.writeString(""); // CallDetails.getCsvFromExtras

        if (uusInfo == null) {
            rr.mParcel.writeInt(0); // UUS information is absent
        } else {
            rr.mParcel.writeInt(1); // UUS information is present
            rr.mParcel.writeInt(uusInfo.getType());
            rr.mParcel.writeInt(uusInfo.getDcs());
            rr.mParcel.writeByteArray(uusInfo.getUserData());
        }

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

    @Override
    public void
    acceptCall (Message result) {
        if(!newril){
            super.acceptCall(result);
            return;
        }
        RILRequest rr
        = RILRequest.obtain(RIL_REQUEST_ANSWER, result);
        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));
        rr.mParcel.writeInt(1);
        rr.mParcel.writeInt(0);
        send(rr);
    }
    
    @Override
    protected Object
    responseIccCardStatus(Parcel p) {
        IccCardApplicationStatus appStatus;

        IccCardStatus cardStatus = new IccCardStatus();
        cardStatus.setCardState(p.readInt());
        cardStatus.setUniversalPinState(p.readInt());
        cardStatus.mGsmUmtsSubscriptionAppIndex = p.readInt();
        cardStatus.mCdmaSubscriptionAppIndex = p.readInt();
        cardStatus.mImsSubscriptionAppIndex = p.readInt();

        int numApplications = p.readInt();

        // limit to maximum allowed applications
        if (numApplications > IccCardStatus.CARD_MAX_APPS) {
            numApplications = IccCardStatus.CARD_MAX_APPS;
        }
        cardStatus.mApplications = new IccCardApplicationStatus[numApplications];

        appStatus = new IccCardApplicationStatus();
        for (int i = 0 ; i < numApplications ; i++) {
            if (i!=0) {
                appStatus = new IccCardApplicationStatus();
            }
            appStatus.app_type       = appStatus.AppTypeFromRILInt(p.readInt());
            appStatus.app_state      = appStatus.AppStateFromRILInt(p.readInt());
            appStatus.perso_substate = appStatus.PersoSubstateFromRILInt(p.readInt());
            appStatus.aid            = p.readString();
            appStatus.app_label      = p.readString();
            appStatus.pin1_replaced  = p.readInt();
            appStatus.pin1           = appStatus.PinStateFromRILInt(p.readInt());
            appStatus.pin2           = appStatus.PinStateFromRILInt(p.readInt());
            p.readInt(); // pin1_num_retries
            p.readInt(); // puk1_num_retries
            p.readInt(); // pin2_num_retries
            p.readInt(); // puk2_num_retries
            p.readInt(); // perso_unblock_retries

            cardStatus.mApplications[i] = appStatus;
        }
        if (numApplications==1 && !isGSM && appStatus.app_type == appStatus.AppTypeFromRILInt(2)) {
            cardStatus.mApplications = new IccCardApplicationStatus[numApplications+2];
            cardStatus.mGsmUmtsSubscriptionAppIndex = 0;
            cardStatus.mApplications[cardStatus.mGsmUmtsSubscriptionAppIndex]=appStatus;
            cardStatus.mCdmaSubscriptionAppIndex = 1;
            cardStatus.mImsSubscriptionAppIndex = 2;
            IccCardApplicationStatus appStatus2 = new IccCardApplicationStatus();
            appStatus2.app_type       = appStatus2.AppTypeFromRILInt(4); // csim state
            appStatus2.app_state      = appStatus.app_state;
            appStatus2.perso_substate = appStatus.perso_substate;
            appStatus2.aid            = appStatus.aid;
            appStatus2.app_label      = appStatus.app_label;
            appStatus2.pin1_replaced  = appStatus.pin1_replaced;
            appStatus2.pin1           = appStatus.pin1;
            appStatus2.pin2           = appStatus.pin2;
            cardStatus.mApplications[cardStatus.mCdmaSubscriptionAppIndex] = appStatus2;
            IccCardApplicationStatus appStatus3 = new IccCardApplicationStatus();
            appStatus3.app_type       = appStatus3.AppTypeFromRILInt(5); // ims state
            appStatus3.app_state      = appStatus.app_state;
            appStatus3.perso_substate = appStatus.perso_substate;
            appStatus3.aid            = appStatus.aid;
            appStatus3.app_label      = appStatus.app_label;
            appStatus3.pin1_replaced  = appStatus.pin1_replaced;
            appStatus3.pin1           = appStatus.pin1;
            appStatus3.pin2           = appStatus.pin2;
            cardStatus.mApplications[cardStatus.mImsSubscriptionAppIndex] = appStatus3;
        }
        return cardStatus;
    }

    @Override
    public void
    sendCdmaSms(byte[] pdu, Message result) {
        smsLock();
        super.sendCdmaSms(pdu, result);
    }

    @Override
    public void
        sendSMS (String smscPDU, String pdu, Message result) {
        smsLock();
        super.sendSMS(smscPDU, pdu, result);
    }

    private void smsLock(){
        synchronized (mSMSLock) {
            long timeoutTime  = SystemClock.elapsedRealtime() + SEND_SMS_TIMEOUT_IN_MS;
            long waitTimeLeft = SEND_SMS_TIMEOUT_IN_MS;
            while (mIsSendingSMS && (waitTimeLeft > 0)) {
                Rlog.d(RILJ_LOG_TAG, "sendSMS() waiting for response of previous SEND_SMS");
                try {
                    mSMSLock.wait(waitTimeLeft);
                } catch (InterruptedException ex) {
                    // ignore the interrupt and rewait for the remainder
                }
                waitTimeLeft = timeoutTime - SystemClock.elapsedRealtime();
            }
            if (waitTimeLeft <= 0) {
                Rlog.e(RILJ_LOG_TAG, "sendSms() timed out waiting for response of previous CDMA_SEND_SMS");
            }
            mIsSendingSMS = true;
        }

    }

    @Override
    protected Object responseSignalStrength(Parcel p) {
        int numInts = 12;
        int response[];

        // Get raw data
        response = new int[numInts];
        for (int i = 0; i < numInts; i++) {
            response[i] = p.readInt();
        }
        //gsm
        response[0] &= 0xff;
        //cdma
        response[2] %= 256;
        response[4] %= 256;
        response[7] &= 0xff;

        return new SignalStrength(response[0], response[1], response[2], response[3], response[4], response[5], response[6], response[7], response[8], response[9], response[10], response[11], true);

    }

    @Override
    public void setPhoneType(int phoneType){
        super.setPhoneType(phoneType);
        isGSM = (phoneType != RILConstants.CDMA_PHONE);
    }

    protected Object
    responseCallList(Parcel p) {
        int num;
        int voiceSettings;
        ArrayList<DriverCall> response;
        DriverCall dc;

        num = p.readInt();
        response = new ArrayList<DriverCall>(num);

        if (RILJ_LOGV) {
            riljLog("responseCallList: num=" + num +
                    " mEmergencyCallbackModeRegistrant=" + mEmergencyCallbackModeRegistrant +
                    " mTestingEmergencyCall=" + mTestingEmergencyCall.get());
        }
        for (int i = 0 ; i < num ; i++) {
            dc = new DriverCall();

            dc.state = DriverCall.stateFromCLCC(p.readInt());
            dc.index = p.readInt() & 0xff;
            dc.TOA = p.readInt();
            dc.isMpty = (0 != p.readInt());
            dc.isMT = (0 != p.readInt());
            dc.als = p.readInt();
            voiceSettings = p.readInt();
            dc.isVoice = (0 != voiceSettings);
            if (isGSM) {
                boolean isVideo = (0 != p.readInt());   // Samsung CallDetails
                int call_type = p.readInt();            // Samsung CallDetails
                int call_domain = p.readInt();          // Samsung CallDetails
                String csv = p.readString();            // Samsung CallDetails
            }
            dc.isVoicePrivacy = (0 != p.readInt());
            dc.number = p.readString();
            int np = p.readInt();
            dc.numberPresentation = DriverCall.presentationFromCLIP(np);
            dc.name = p.readString();
            dc.namePresentation = p.readInt();
            int uusInfoPresent = p.readInt();
            if (uusInfoPresent == 1) {
                dc.uusInfo = new UUSInfo();
                dc.uusInfo.setType(p.readInt());
                dc.uusInfo.setDcs(p.readInt());
                byte[] userData = p.createByteArray();
                dc.uusInfo.setUserData(userData);
                riljLogv(String.format("Incoming UUS : type=%d, dcs=%d, length=%d",
                                dc.uusInfo.getType(), dc.uusInfo.getDcs(),
                                dc.uusInfo.getUserData().length));
                riljLogv("Incoming UUS : data (string)="
                        + new String(dc.uusInfo.getUserData()));
                riljLogv("Incoming UUS : data (hex): "
                        + IccUtils.bytesToHexString(dc.uusInfo.getUserData()));
            } else {
                riljLogv("Incoming UUS : NOT present!");
            }

            // Make sure there's a leading + on addresses with a TOA of 145
            dc.number = PhoneNumberUtils.stringFromStringAndTOA(dc.number, dc.TOA);

            response.add(dc);

            if (dc.isVoicePrivacy) {
                mVoicePrivacyOnRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is enabled");
            } else {
                mVoicePrivacyOffRegistrants.notifyRegistrants();
                riljLog("InCall VoicePrivacy is disabled");
            }
        }

        Collections.sort(response);

        if ((num == 0) && mTestingEmergencyCall.getAndSet(false)) {
            if (mEmergencyCallbackModeRegistrant != null) {
                riljLog("responseCallList: call ended, testing emergency call," +
                            " notify ECM Registrants");
                mEmergencyCallbackModeRegistrant.notifyRegistrant();
            }
        }

        return response;
    }

    @Override
    protected void
    processUnsolicited (Parcel p) {
        Object ret;
        int dataPosition = p.dataPosition();
        int response = p.readInt();

        switch(response) {
            case RIL_UNSOL_RIL_CONNECTED:
                ret = responseInts(p);
                setRadioPower(false, null);
                setPreferredNetworkType(mPreferredNetworkType, null);
                setCdmaSubscriptionSource(mCdmaSubscription, null);
                if(mRilVersion >= 8)
                    setCellInfoListRate(Integer.MAX_VALUE, null);
                notifyRegistrantsRilConnectionChanged(((int[])ret)[0]);
                break;
            // SAMSUNG STATES
            case 11010: // RIL_UNSOL_AM:
                ret = responseString(p);
                String amString = (String) ret;
                Rlog.d(RILJ_LOG_TAG, "Executing AM: " + amString);

                try {
                    Runtime.getRuntime().exec("am " + amString);
                } catch (IOException e) {
                    e.printStackTrace();
                    Rlog.e(RILJ_LOG_TAG, "am " + amString + " could not be executed.");
                }
                break;
            case 11021: // RIL_UNSOL_RESPONSE_HANDOVER:
                ret = responseVoid(p);
                break;
            case 1036:
                ret = responseVoid(p);
                break;
            case 11017: // RIL_UNSOL_WB_AMR_STATE:
                ret = responseInts(p);
                setWbAmr(((int[])ret)[0]);
                break;
            default:
                // Rewind the Parcel
                p.setDataPosition(dataPosition);

                // Forward responses that we are not overriding to the super class
                super.processUnsolicited(p);
                return;
        }

    }

    @Override
    protected RILRequest
    processSolicited (Parcel p) {
        int serial, error;
        boolean found = false;
        int dataPosition = p.dataPosition(); // save off position within the Parcel
        serial = p.readInt();
        error = p.readInt();
        RILRequest rr = null;
        /* Pre-process the reply before popping it */
        synchronized (mRequestList) {
            RILRequest tr = mRequestList.get(serial);
            if (tr != null && tr.mSerial == serial) {
                if (error == 0 || p.dataAvail() > 0) {
                    try {switch (tr.mRequest) {
                            /* Get those we're interested in */
                        case RIL_REQUEST_DATA_REGISTRATION_STATE:
                        case RIL_REQUEST_VOICE_REGISTRATION_STATE:
                        case RIL_REQUEST_OPERATOR:
                            rr = tr;
                            break;
                    }} catch (Throwable thr) {
                        // Exceptions here usually mean invalid RIL responses
                        if (tr.mResult != null) {
                            AsyncResult.forMessage(tr.mResult, null, thr);
                            tr.mResult.sendToTarget();
                        }
                        return tr;
                    }
                }
            }
        }
        if (rr == null) {
            /* Nothing we care about, go up */
            p.setDataPosition(dataPosition);
            // Forward responses that we are not overriding to the super class
            return super.processSolicited(p);
        }
        rr = findAndRemoveRequestFromList(serial);
        if (rr == null) {
            return rr;
        }
        Object ret = null;
        if (error == 0 || p.dataAvail() > 0) {
		    switch (rr.mRequest) {
                case RIL_REQUEST_OPERATOR: ret = operatorCheck(p); break;
                case RIL_REQUEST_VOICE_REGISTRATION_STATE: ret = responseVoiceDataRegistrationState(p); break;
                case RIL_REQUEST_DATA_REGISTRATION_STATE: ret = responseDataRegistrationState(p); break;
                default:
                    throw new RuntimeException("Unrecognized solicited response: " + rr.mRequest);	
            }
            //break;
        }
        if (RILJ_LOGD) riljLog(rr.serialString() + "< " + requestToString(rr.mRequest)
                               + " " + retToString(rr.mRequest, ret));
        if (rr.mResult != null) {
            AsyncResult.forMessage(rr.mResult, ret, null);
            rr.mResult.sendToTarget();
        }
        return rr;
    }

    private Object
    operatorCheck(Parcel p) {
        String response[] = (String[])responseStrings(p);
        for(int i=0; i<2; i++){
            if (response[i]!= null){
                response[i] = Operators.operatorReplace(response[i]);
            }
        }
        return response;
    }

    private Object
    responseDataRegistrationState(Parcel p) {
        String response[] = (String[])responseStrings(p);
        if (isGSM){
            /* DANGER WILL ROBINSON
             * In some cases from Vodaphone we are receiving a RAT of 102
             * while in tunnels of the metro. Lets Assume that if we
             * receive 102 we actually want a RAT of 2 for EDGE service */
            if (response.length > 4 &&
                response[0].equals("1") &&
                response[3].equals("102")) {
                response[3] = "2";
            }
        }
        return responseVoiceDataRegistrationState(p);
    }
    private Object
    responseVoiceDataRegistrationState(Parcel p) {
        String response[] = (String[])responseStrings(p);
        if (isGSM){
            return response;
        }
        if (response.length>=10){
            for(int i=6; i<=9; i++){
                if (response[i]== null){
                    response[i]=Integer.toString(Integer.MAX_VALUE);
                } else {
                    try {
                        Integer.parseInt(response[i]);
                    } catch(NumberFormatException e) {
                        response[i]=Integer.toString(Integer.parseInt(response[i],16));
                    }
                }
            }
        }

        return response;
    }

    private void setWbAmr(int state) {
        if (state == 1) {
            Rlog.d(RILJ_LOG_TAG, "setWbAmr(): setting audio parameter - wb_amr=on");
            mAudioManager.setParameters("wide_voice_enable=true");
        }else if (state == 0) {
            Rlog.d(RILJ_LOG_TAG, "setWbAmr(): setting audio parameter - wb_amr=off");
            mAudioManager.setParameters("wide_voice_enable=false");
        }
    }

    @Override
    protected void notifyRegistrantsCdmaInfoRec(CdmaInformationRecords infoRec) {
        final int response = RIL_UNSOL_CDMA_INFO_REC;

        if (infoRec.record instanceof CdmaSignalInfoRec) {
            CdmaSignalInfoRec sir = (CdmaSignalInfoRec) infoRec.record;
            if (sir != null
                    && sir.isPresent
                    && sir.signalType == SignalToneUtil.IS95_CONST_IR_SIGNAL_IS54B
                    && sir.alertPitch == SignalToneUtil.IS95_CONST_IR_ALERT_MED
                    && sir.signal == SignalToneUtil.IS95_CONST_IR_SIG_IS54B_L) {

                Rlog.d(RILJ_LOG_TAG, "Dropping \"" + responseToString(response) + " "
                        + retToString(response, sir)
                        + "\" to prevent \"ring of death\" bug.");
                return;
            }
        }

        super.notifyRegistrantsCdmaInfoRec(infoRec);
    }

    @Override
    protected Object
    responseSMS(Parcel p) {

        synchronized (mSMSLock) {
            mIsSendingSMS = false;
            mSMSLock.notify();
        }

        return super.responseSMS(p);
    }

    @Override
    protected Object
    responseOperatorInfos(Parcel p) {
        String strings[] = (String [])responseStrings(p);
        ArrayList<OperatorInfo> ret;

        if (strings.length % mQANElements != 0) {
            throw new RuntimeException(
                                       "RIL_REQUEST_QUERY_AVAILABLE_NETWORKS: invalid response. Got "
                                       + strings.length + " strings, expected multiple of " + mQANElements);
        }

        ret = new ArrayList<OperatorInfo>(strings.length / mQANElements);
        Operators init = null;
        if (strings.length != 0) {
            init = new Operators();
        }
        for (int i = 0 ; i < strings.length ; i += mQANElements) {
            String temp = init.unOptimizedOperatorReplace(strings[i+0]);
            ret.add (
                     new OperatorInfo(
                                      temp, //operatorAlphaLong
                                      temp,//operatorAlphaShort
                                      strings[i+2],//operatorNumeric
                                      strings[i+3]));//state
        }

        return ret;
    }

    @Override
    public void getImsRegistrationState(Message result) {
        if(mRilVersion >= 8)
            super.getImsRegistrationState(result);
        else {
            if (result != null) {
                CommandException ex = new CommandException(
                    CommandException.Error.REQUEST_NOT_SUPPORTED);
                AsyncResult.forMessage(result, null, ex);
                result.sendToTarget();
            }
        }
    }

    static final int RIL_REQUEST_DIAL_EMERGENCY = 10016;
    public void
    dialEmergencyCall(String address, int clirMode, Message result) {
        RILRequest rr;

        rr = RILRequest.obtain(RIL_REQUEST_DIAL_EMERGENCY, result);
        rr.mParcel.writeString(address);
        rr.mParcel.writeInt(clirMode);
        rr.mParcel.writeInt(0);        // CallDetails.call_type
        rr.mParcel.writeInt(3);        // CallDetails.call_domain
        rr.mParcel.writeString("");    // CallDetails.getCsvFromExtra
        rr.mParcel.writeInt(0);        // Unknown

        if (RILJ_LOGD) riljLog(rr.serialString() + "> " + requestToString(rr.mRequest));

        send(rr);
    }

}