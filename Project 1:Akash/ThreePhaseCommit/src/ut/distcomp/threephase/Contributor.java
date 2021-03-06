package ut.distcomp.threephase;

import ut.distcomp.framework.Config;
import ut.distcomp.framework.NetController;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Created with IntelliJ IDEA.
 * User: akash
 * Date: 10/5/13
 * Time: 1:10 AM
 * To change this template use File | Settings | File Templates.
 */
public class Contributor {

    private int currCoordinator;

    private static final String FILEPATH = "./log/";
    private static final String FILENAME = "Log";
    private static final String EXTENSION = ".log";
    private static final String DTLOG = "DTLog";
    private PlayList playlist;
    private NetController controller;
    private Config config;
    public Logger logger;
    private DTlog dtlog;
    private StateManager automata;
    private List<ParseMessage> pmList;
    private long timeout = 2000;
    private HashSet<Integer> upSet;
    private Coordinator coordinator;
    private Participant participant;
    private HashMap<String, TransitionMessage> old_decisions;
    private HashMap<Integer, HashSet<Integer>> recovery_upsets;
    private ParticipantSTATE myLastState;
    HashSet<ParseMessage> recoveryList;

    /**
     *
     * Failure Simulation Variables
     */
    private int precommitCount;
    private int commitCount;
    private int deathAfter_n;
    private int deathAfter_src;
    private int deathAfter_dest;

    public Contributor(Config config) {

        try {

            this.config = config;

            /**
             * Initializing failure parameters
             */
            timeout += config.delay;
            precommitCount = config.precommitCount;
            commitCount    = config.commitCount;
            deathAfter_n   = config.deathAfter_n;
            deathAfter_src = config.deathAfter_src;
            deathAfter_dest =  config.deathAfter_dest;


            //Init logger
            this.logger = Logger.getLogger(this.getClass().getName());
            FileHandler fileHandler = null;
            fileHandler = new FileHandler(FILEPATH + FILENAME + String.valueOf(config.procNum) + EXTENSION);
            fileHandler.setFormatter(new SimpleFormatter());
            this.logger.addHandler(fileHandler);

            logger.log(Level.INFO, "deathAfter_n "+ deathAfter_n + " deathAfter_src " + deathAfter_src + "deathAfter_dest " + deathAfter_dest);
            automata = new StateManager();
            playlist = new PlayList(config.procNum);
            controller = new NetController(config);
            System.out.println("Created Coordinator " + config.procNum);
            pmList = new ArrayList<ParseMessage>();
            recovery_upsets = new HashMap<Integer, HashSet<Integer>>();
            recoveryList = new HashSet<ParseMessage>();

            currCoordinator = 0;
            upSet = new HashSet<Integer>();
            for (int i=0; i<config.numProcesses; i++)
                upSet.add(i);


            /**
             * To track all the old decisions
             */
            old_decisions = new HashMap<String, TransitionMessage>();

            //Recovery()
            String filename = FILEPATH + DTLOG + String.valueOf(config.procNum) + EXTENSION;
            Recover recover = new Recover(filename, config.procNum);
            recover.parseLogFile();

            //Init DTLog
            this.dtlog = DTlog.getInstance(config.procNum);

            if(!(recover.isRecoverValid())) {

                dtlog.log("Upset="+upSet);
                // Normal Execution
                if(isCoordinator()){
                    coordinator = new Coordinator();
                    myLastState = ParticipantSTATE.INVALID;
                    coordinator.pollSockCoordinator();
                }
                else{
                    participant = new Participant();
                    myLastState = ParticipantSTATE.WAIT_FOR_VOTE_REQ;
                    participant.getMessagesUntilTimeOut();
                }
            } else {
                //  Reinitialize upset and playlist
                //  RECOVERY MODE

                upSet = recover.getUpset();
                playlist = recover.getPlaylist();
                old_decisions = recover.getOld_decisions();
                myLastState = recover.getMyState();

                if(recover.isDecisionPending()) {
                    // Send messages to all of upset
                    getMeADecision(recover.getPendingRequest());

                    resetCoordinator();
                }
                if(isCoordinator()){
                    coordinator = new Coordinator();
                    coordinator.pollSockCoordinator();
                }
                else{
                    participant = new Participant();
                    participant.getMessagesUntilTimeOut();
                }
            }

        }
        catch (IOException e) {
            logger.log(Level.SEVERE,"<<<"+ config.procNum + ">>>> Exception in the constructor");
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE,"<<<"+ config.procNum + ">>>> Exception in the constructor");
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (Exception e) {
            logger.log(Level.SEVERE,"<<<"+ config.procNum + ">>>> Exception in the constructor");
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    private boolean isCoordinator(){
        if(config.procNum == currCoordinator)
         return  true;

        return false;
    }

    private void resetCoordinator() {
        currCoordinator=0;
    }

    private void getMeADecision(ParseMessage request) throws Exception {

        boolean done = false;

        while(!done) {
            sendRecoverRequest(request);
            done = getRecoverResponse(request);

            if(!done){
                if(shouldIDecide()) {
                    logger.log(Level.INFO,"Total Failure I Gotta decide for " + request.serialize() );
                   done = goAheadAndTakeDecision(request);
                }
            }
        }
        //Send reply to everyone who requested for a decision.
        processRecoveryReq(recoveryList);
        recoveryList.clear();
    }

    /**
     * Will only return true iff everyone has sent you a recovery request (Commented out part
     * Will only return true iff everyone in your upset has sent you a recovery request
     * @return
     */
    private boolean shouldIDecide() {
        boolean readyToDecide=false;
        int count=0;

        for(Integer item: upSet) {
            if(recovery_upsets.containsKey(item)){
                logger.log(Level.INFO, "Got a recovery Request from:"+item+ " with upSet:"+recovery_upsets.get(item));
                count++;
            }
        }
        logger.log(Level.INFO, "shouldIDecide(), Count:"+ count+" UpsetSize:" + upSet.size());
        if(count == (upSet.size() - 1)){
            return true;
        }
        return false;

//        for(int i=0; i<config.numProcesses;i++) {
//            if(recovery_upsets.containsKey(Integer.valueOf(i)))
//                count++;
//        }
//
//        if(count == (config.numProcesses - 1)){
//            return true;
//        }
//        return false;
    }

    /**
     * Decides to commit if a precommit message exists. Else Abort
     * TODO: Does it need to send a prcommit message?
     * @param pm
     * @throws Exception
     */
    private boolean goAheadAndTakeDecision(ParseMessage pm) throws Exception {

        int smallest = Integer.MAX_VALUE;
        for(Integer i: upSet){
            if(i.intValue() < smallest){
                smallest = i.intValue();
            }
        }

        // Either you are the only one left. Or You are the one with the lowest PID.
        if(upSet.size() == 1 || config.procNum == smallest) {
//            logger.log(Level.INFO, "INside GoaheadandTakeaDecision " + upSet + ":" + myLastState + ":" + pm.serialize());
            recoverFromTotalFailure(config.procNum, myLastState, pm);
            return true;
        }
        return false;

    }

    private void recoverFromTotalFailure(int procNum, ParticipantSTATE state, ParseMessage transaction) throws Exception {

        logger.log(Level.INFO, "<<<" + config.procNum + ">>> I am the PSUEDO COORDINATOR IN TOTAL FAILURE with state " + state);

        //Decide my state
        List<ParseMessage> list = new ArrayList<ParseMessage>();
        TransitionMessage myDecision = terminationProtocol(state, list);
        logger.log(Level.INFO, "<<<" + config.procNum + ">>> I decide to " + myDecision);

        transaction.setSource(String.valueOf(config.procNum));
        transaction.setMessageHeader(myDecision.toString());

        updateDecision(transaction);

        resetCoordinator();

        return;
    }
    private void sendRecoverRequest(ParseMessage pmrequest){
        pmrequest.setMessageHeader(TransitionMessage.RECOVERY_REQ.toString());
        pmrequest.setSource(String.valueOf(config.procNum));
        pmrequest.setUpSet(upSet);

        String request = pmrequest.serializeWithUpset();

        for(Integer item: upSet) {
            if(config.procNum == item)
                continue;
            controller.sendMsg(item.intValue(), request);
            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Sending Recovery-Req = " + request +" to:"+ item.intValue());
        }
    }

    private void processRecoveryReq(HashSet<ParseMessage> recovery_req) {
        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Handling the pending recovery requests now.");
        String source;

        for(ParseMessage req: recovery_req) {
            if(old_decisions.containsKey(req.getTransaction()))  {
                source = req.getSource();
                logger.log(Level.INFO, "Whats is the source in Recovery? "+ source);
                req.setSource(String.valueOf(config.procNum));
                logger.log(Level.INFO, "Whats is the source while sending Recovery "+ req.getSource());
                req.setMessageHeader(old_decisions.get(req.getTransaction()).toString());
                if(!source.equals(req.getSource()))
                controller.sendMsg(Integer.parseInt(source), req.serialize());
            }
        }
    }

    private boolean getRecoverResponse(ParseMessage request) throws Exception {
        List<String> messages = new ArrayList<String>();
        ParseMessage pmTemp = new ParseMessage();
        long startTime = (System.currentTimeMillis()+timeout);
        long timer = timeout/10;
        boolean result = false;

        while(System.currentTimeMillis() < startTime) {

            Thread.sleep(timer);
            messages = (controller.getReceivedMsgs());

            for(String m:messages) {
                ParseMessage nextreceivedPM = new ParseMessage(m);
                logger.log(Level.INFO, "<<<" + config.procNum + ">> " + nextreceivedPM.serialize());
                if(nextreceivedPM.getSource().equalsIgnoreCase("client") ||
                        nextreceivedPM.getMessageHeader().equalsIgnoreCase(TransitionMessage.UR_ELECTED.toString())) {
                    continue;
                }

                boolean validSource = upSet.contains(Integer.parseInt(nextreceivedPM.getSource()));

                if((nextreceivedPM.getMessageHeader().equalsIgnoreCase(TransitionMessage.ABORT.toString()) ||
                        nextreceivedPM.getMessageHeader().equalsIgnoreCase(TransitionMessage.COMMIT.toString())) &&
                        nextreceivedPM.getTransaction().equalsIgnoreCase(request.getTransaction()) &&
                        validSource) {
                    pmTemp = nextreceivedPM;
                    result = true;
                    break;
                } else if(nextreceivedPM.getMessageHeader().equalsIgnoreCase(TransitionMessage.RECOVERY_REQ.toString()) &&
                            nextreceivedPM.getTransaction().equalsIgnoreCase(request.getTransaction()) &&
                            validSource) {
                    recovery_upsets.put(Integer.valueOf(Integer.parseInt(nextreceivedPM.getSource())), nextreceivedPM.getUpSet());
                    recoveryList.add(nextreceivedPM);
                }
            }

        }
        logger.log(Level.INFO , "<<<" + config.procNum + ">>> Timed out for Recovery response. Resending the request" );

        if(result) {
            updateDecision(pmTemp);
        }
        return result;
    }

    private void updateDecision(ParseMessage request) throws Exception {
        if(request.getMessageHeader().equalsIgnoreCase(TransitionMessage.COMMIT.toString()))
            playlist.actOnDecision(request);

        dtlog.log("Decision=" + request.serialize());
        old_decisions.put(request.getTransaction(), TransitionMessage.valueOf(request.getMessageHeader().toUpperCase()));
    }

    private void promoteMe(int procNum, ParticipantSTATE state, ParseMessage transaction) throws Exception {

        /**
         * Send State-Req
         * Receive message from all participant
         * Decide on a coordState state
         * new Participant().deleteInstance()
         * Coordinator c = Coordinator.getInstance();
         * c.setCoordState(state)
         * c.pollsock()
         */

        currCoordinator = procNum;

        logger.log(Level.INFO, "<<<" + config.procNum + ">>> I am the coordinator now YEYEYEY :D with State " + state);
        //Send state request
        sendStateReq(transaction);

        // Collect until timeout
        List<ParseMessage> responses = getResponseToStateReq();

        //Decide my state
        TransitionMessage myDecision = terminationProtocol(state, responses);
        logger.log(Level.INFO, "<<<" + config.procNum + ">>> I decide to " + myDecision);

        //TODO DOnt call if responses are 0!
        sendDecision(state, myDecision, responses, transaction);
        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Sent my decision");

        logger.log(Level.INFO, "Going back to being a participant again Booya!");

        resetCoordinator();

        return;

    }

    private void sendStateReq(ParseMessage pmrequest) {
        pmrequest.setMessageHeader(TransitionMessage.STATE_REQ.toString());
        pmrequest.setSource(String.valueOf(config.procNum));
        String request = pmrequest.serialize();

        //TODO Change if liveSet of processes in implemented
        for(Integer i: upSet) {
            if(config.procNum == i.intValue())
                continue;

            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Sending State-Req = " + request + " to " + i);
            controller.sendMsg(i.intValue(), request);
        }
    }

    private List<ParseMessage> getResponseToStateReq() throws Exception {
        List<String> messages = new ArrayList<String>();
        List<ParseMessage> pmTemp = new ArrayList<ParseMessage>();
        long startTime = (System.currentTimeMillis()+timeout);
        long timer = timeout/10;
        boolean timeout_flag = false;


        while(System.currentTimeMillis() < startTime) {

            //Thread.sleep(timer);
            messages = controller.getReceivedMsgs();
            for(String m:messages) {
                ParseMessage nextreceivedPM = new ParseMessage(m);

                if(nextreceivedPM.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.ABORTED.toString()) ||
                    nextreceivedPM.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.COMMITTED.toString()) ||
                    nextreceivedPM.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.COMMITTABLE.toString()) ||
                    nextreceivedPM.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.UNCERTAIN.toString()))
                {
                    pmTemp.add(nextreceivedPM);
                    timeout_flag = true;
                }

                logger.log(Level.INFO, "<<<" + config.procNum + ">>> Response to state request from [" + nextreceivedPM.getSource() + "] with " + nextreceivedPM.getMessageHeader());
            }

            if(pmTemp.size() == upSet.size()-1) {
                timeout_flag = true;
                break;
            }
        }
        if(!timeout_flag){
            logger.log(Level.INFO , "<<<" + config.procNum + ">>> New Coordinator timed out. Recvd " + pmTemp.size() + " responses." );
            updateUpset(pmTemp);
        }
        return pmTemp;
    }

    private TransitionMessage terminationProtocol(ParticipantSTATE myState, List<ParseMessage> list ) {
        logger.log(Level.INFO, "In termination protocol with state " + myState);
        logger.log(Level.INFO, "and with number of Responses"+ list.size());
        TransitionMessage termination_decision = null;
        if(list.isEmpty()) {
            switch(myState) {
                case ABORTED:
                case UNCERTAIN: termination_decision = TransitionMessage.ABORT; break;
                case COMMITTED:
                case COMMITTABLE: termination_decision = TransitionMessage.COMMIT;    break;
            }
        } else {
            int count_commitable = 0;
            int count_uncertain = 0;
            boolean commit_flag = false;
            boolean abort_flag = false;

            for(ParseMessage item:list) {
                if(item.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.ABORTED.toString())) {
                    abort_flag = true;
                    break;
                } else if(item.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.COMMITTED.toString())) {
                    commit_flag = true;
                    break;
                } else if(item.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.UNCERTAIN.toString())) {
                    count_uncertain ++;
                    continue;
                } else if(item.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.COMMITTABLE.toString())) {
                    count_commitable ++;
                    continue;
                }
            }
            switch(myState) {
                case ABORTED: abort_flag = true; break;
                case COMMITTED: commit_flag = true; break;
                case UNCERTAIN: count_uncertain ++; break;
                case COMMITTABLE: count_commitable++; break;
            }

            if(abort_flag) {
                termination_decision = TransitionMessage.ABORT;
            } else if(commit_flag) {
                termination_decision = TransitionMessage.COMMIT;
            } else if(count_uncertain == list.size() + 1) {
                termination_decision = TransitionMessage.ABORT;
            } else if(count_commitable > 0) {
                termination_decision = TransitionMessage.PRECOMMIT;
            }
            logger.log(Level.INFO, "Count Uncertain " + count_uncertain );
            logger.log(Level.INFO, "commit_flag " + commit_flag );
            logger.log(Level.INFO, "abort_flag " + abort_flag );
            logger.log(Level.INFO, "Count Commitable " + count_commitable );
        }


        return termination_decision;
    }

    public void sendDecision(ParticipantSTATE myState,TransitionMessage decision, List<ParseMessage> responses, ParseMessage transaction) throws Exception {

        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Preparing decision for state request ---> my state/decision " + decision);
        ParseMessage myDecision = new ParseMessage();

        logger.log(Level.INFO , "<<<" + config.procNum + ">>> Sending Decison = " + decision);
        String sendTo = "";


        myDecision = transaction;
        myDecision.setSource(String.valueOf(currCoordinator));
        myDecision.setMessageHeader(decision.toString());

        /**
         * Adding to my list of old decisions
         */
         if(decision.equals(TransitionMessage.COMMIT)||decision.equals(TransitionMessage.ABORT))
            old_decisions.put(myDecision.getTransaction(), decision);

        switch (decision) {
            case ABORT:
            case COMMIT:
                /**
                 * Response to Participants
                 */
                for(ParseMessage response: responses ) {

                    sendTo = response.getSource();
                    response.setSource(String.valueOf(currCoordinator));
                    logger.log(Level.INFO, "Checking termination protocol " + response.serialize() + " for " + sendTo);
                    if((!response.getMessageHeader().equalsIgnoreCase(decision.toString())) &&
                            upSet.contains(Integer.parseInt(sendTo))) {
                        response.setMessageHeader(decision.toString());
                        logger.log(Level.INFO, "Sending the decision of termination protocol " + response.serialize() + " to " + sendTo);
                        controller.sendMsg(Integer.parseInt(sendTo), response.serialize());
                    }

                }
                /**
                 * Coordinator Operation - Do I need to update my own logs and State?
                 */
                if(decision.equals(TransitionMessage.COMMIT)){
                    if(!myState.equals(ParticipantSTATE.COMMITTED))
                    {
                        dtlog.log("Decision=" + myDecision.serialize());
                        playlist.actOnDecision(myDecision);
                    }
                }else if(!myState.equals(ParticipantSTATE.ABORTED))
                    dtlog.log("Decision=" + myDecision.serialize());
                break;

            case PRECOMMIT:

                dtlog.log("Precommit=" + myDecision.serialize());
                for(ParseMessage response: responses ) {

                    sendTo = response.getSource();
                    response.setSource(String.valueOf(currCoordinator));

                    if((!response.getMessageHeader().equalsIgnoreCase(decision.toString())) &&
                            response.getMessageHeader().equalsIgnoreCase(ParticipantSTATE.UNCERTAIN.toString()) &&
                            upSet.contains(Integer.parseInt(sendTo))) {
                        response.setMessageHeader(decision.toString());
                        controller.sendMsg(Integer.parseInt(sendTo), response.serialize());
                    }
                }
                long startTime = (System.currentTimeMillis()+timeout/2);
                long smallTimeout = timeout/2;
                boolean timoutflag = false;

                while(System.currentTimeMillis() < startTime) {
                    List<String> messages = (controller.getReceivedMsgs());
                    //logger.log(Level.INFO , "<<<" + config.procNum + ">> Trying to receive a msg" + messages.size());
                    for(String m:messages) {
                       ParseMessage msg = new ParseMessage(m);
                       if(msg.getMessageHeader().equalsIgnoreCase(TransitionMessage.ACK.toString())) {
                           logger.log(Level.INFO, "<<<" + config.procNum + ">>> Received ACK from " + msg.getSource() + " Complete message = " + msg.serialize() );
                       }
                    }
                }
                transaction.setMessageHeader(TransitionMessage.COMMIT.toString());
                transaction.setSource(String.valueOf(config.procNum));
                String request = transaction.serialize();
                logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send Decision = " + request);
                dtlog.log("Decision=" + request);
                for(int i=0; i<config.numProcesses ;i++) {
                    if(config.procNum == i)
                        continue;
                    controller.sendMsg(i, request );

                    if(commitCount == i)
                        System.exit(1);
                }
                playlist.actOnDecision(transaction);
                break;
        }

    }

    public void deathAfter(ParseMessage m){
        if(deathAfter_src == Integer.parseInt(m.getSource())){
            deathAfter_n--;
            logger.log(Level.INFO, "Cuurent value of n = " + deathAfter_n);
            if(deathAfter_n == 0){
                logger.log(Level.INFO,"Exiting Deterministically after receiving " + config.deathAfter_n +" messages from " + deathAfter_src);
                System.exit(1);
            }
        }
    }

    public  class Participant {
        private ParticipantSTATE participantState;
        private ParseMessage currTransaction;
        HashSet<ParseMessage> recovery_req;

        public Participant() {
            try {
                participantState = ParticipantSTATE.WAIT_FOR_VOTE_REQ;
                currTransaction = null;
                recovery_req = new HashSet<ParseMessage>();

            } catch(Exception e) {

            }
        }

        private ParticipantSTATE getParticipantState(){
            return participantState;
        }

        private void setParticipantState(ParticipantSTATE state){
            participantState =  state;
            return;
        }

        private ParseMessage getCurrTransaction(){
            return currTransaction;
        }

        private void setCurrTransaction(ParseMessage m){
            currTransaction = m;
            return;
        }

        private void ProcessReceivedMessage(ParseMessage m) throws Exception {
            if(deathAfter_dest == config.procNum){
                deathAfter(m);
            }
            if(m.getMessageHeader().toString().equals(TransitionMessage.VOTE_REQ.toString())){

                /**
                 * Reinitializing the upset for an new transaction
                 */
                upSet = new HashSet<Integer>();
                for (int i=0; i<config.numProcesses; i++)
                    upSet.add(i);

                dtlog.log("Upset="+upSet);

                ParticipantSTATE tempState = automata.getNextParticipantState(getParticipantState(),m, playlist);
                if(tempState != ParticipantSTATE.INVALID){

                    sendVote(tempState, m);
                    logger.log(Level.INFO, "Just sent my vote " + m.serialize());
                    setCurrTransaction(m);


                }
            }
            else if(m.getMessageHeader().toString().equals(TransitionMessage.PRECOMMIT.toString())){

                logger.log(Level.INFO, "<<<" + config.procNum + ">>> Received a precommit from" + m.getSource());
                ParticipantSTATE tempState = null;
                tempState = automata.getNextParticipantState(getParticipantState(),m, playlist);
                if(tempState != ParticipantSTATE.INVALID)
                {
                    dtlog.log("Precommit="+m.serialize());
                    sendACK(tempState, m);
                }

            }
            else if (m.getMessageHeader().toString().equals(TransitionMessage.COMMIT.toString())){

                logger.log(Level.INFO, "<<<" + config.procNum + ">>> Committing now " + m.serialize());
                ParticipantSTATE tempState = null;
                tempState = automata.getNextParticipantState(getParticipantState(),m, playlist);
                if(tempState != ParticipantSTATE.INVALID){
                    goForCommit(tempState, m);
                    /**
                     * Adding decision to the HashMap
                     */
                    old_decisions.put(m.getTransaction(), TransitionMessage.COMMIT);
                }

            }
            else if (m.getMessageHeader().toString().equals(TransitionMessage.ABORT.toString())){


                logger.log(Level.INFO, "<<<" + config.procNum + ">>> Aborting now " + m.serialize());
                ParticipantSTATE tempState = null;
                tempState = automata.getNextParticipantState(getParticipantState(),m, playlist);
                if(tempState != ParticipantSTATE.INVALID) {
                    dtlog.log("Decision="+m.serialize());
                    setParticipantState(ParticipantSTATE.WAIT_FOR_VOTE_REQ);
                    /**
                     * Adding decision to the HashMap
                     */
                    old_decisions.put(m.getTransaction(), TransitionMessage.ABORT);

                }

            }
            else if (m.getMessageHeader().toString().equals(TransitionMessage.STATE_REQ.toString())){
                ParticipantSTATE tempState = null;
                tempState = automata.getNextParticipantState(getParticipantState(),m, playlist);
                if(tempState != ParticipantSTATE.INVALID){
                    if(upSet.contains(Integer.parseInt(m.getSource()))){

                        assert(Integer.parseInt(m.getSource()) < config.procNum);
                        int start = Integer.parseInt(m.getSource());
                        start--;
                        while(start >= 0){
                            upSet.remove(start);
                            start--;
                        }

                        upSet.add(config.procNum);

                        currCoordinator = Integer.parseInt(m.getSource());
                        //sendParticipantState(participantState, m);
                        dtlog.log("Upset=" + upSet);

                        if(old_decisions.containsKey(m.getTransaction())){
                            String sendTo = m.getSource();
                            m.setSource(String.valueOf(config.procNum));
                            m.setMessageHeader(old_decisions.get(m.getTransaction()).toString());
                            controller.sendMsg(Integer.parseInt(sendTo), m.serialize());
                        }else
                            sendParticipantState(participantState, m);
                    }
                    else{
                        // it's a stale request
                        // ignore
                    }
                }

            }
            else if (m.getMessageHeader().toString().equals(TransitionMessage.UR_ELECTED.toString())){
                ParticipantSTATE tempState = null;
                tempState = automata.getNextParticipantState(getParticipantState(),m, playlist);
                if(tempState != ParticipantSTATE.INVALID){

                    if(upSet.contains(Integer.parseInt(m.getSource()))){
                        int start = config.procNum;
                        start--;
                        while(start >= 0){
                            upSet.remove(start);
                            start--;
                        }
                        upSet.add(config.procNum);
                        dtlog.log("Upset=" + upSet);
                    }

                    if(old_decisions.containsKey(m.getTransaction()) &&
                            getParticipantState().equals(ParticipantSTATE.WAIT_FOR_VOTE_REQ)){

                        if(old_decisions.get(m.getTransaction()).equals(TransitionMessage.COMMIT)) {
                            promoteMe(config.procNum, ParticipantSTATE.COMMITTED, m);
                        } else {
                            promoteMe(config.procNum, ParticipantSTATE.ABORTED, m);
                        }
                    }else{
                        promoteMe(config.procNum,getParticipantState(),m);

                    }
                    logger.log(Level.INFO, " I'M BACK!");
                    setParticipantState(ParticipantSTATE.WAIT_FOR_VOTE_REQ);
                }
            }
            else if (m.getMessageHeader().toString().equals(TransitionMessage.RECOVERY_REQ.toString())){
                    if(!m.getSource().equals(String.valueOf(config.procNum)))
                    recovery_req.add(m);
            }
            return;

        }

        protected Queue<ParseMessage> getMessagesUntilTimeOut() throws Exception {

            int  ur_elected_count = 0;

            while(true){
                List<String> messages;
//                Queue<ParseMessage> pmTemp = new ConcurrentLinkedQueue<ParseMessage>();
                long startTime = (System.currentTimeMillis()+timeout);
                long smallTimeout = timeout/10;
                boolean atleastoneMsgReceived = false;

                while(System.currentTimeMillis() < startTime) {
                    Thread.sleep(smallTimeout);
                    messages = (controller.getReceivedMsgs());
                    //logger.log(Level.INFO , "<<<" + config.procNum + ">> Trying to receive a msg" + messages.size());
                    for(String m:messages) {
                        logger.log(Level.INFO , "<<<" + config.procNum + ">> " + "message "+ m);
                        ParseMessage nextreceivedPM = new ParseMessage(m);

                        /**
                         * HAndling the case of multiple UR_elected messages
                         */
                        if(nextreceivedPM.getMessageHeader().equals(TransitionMessage.UR_ELECTED.toString()))
                        {
                                ur_elected_count ++;
                                if(ur_elected_count < 2)
                                {
                                    ProcessReceivedMessage(nextreceivedPM);
                                    atleastoneMsgReceived = true;
                                    break;
                                }
                        }
                        else{
                                ProcessReceivedMessage(nextreceivedPM);
                                atleastoneMsgReceived = true;
                        }
                    }
                }
                logger.log(Level.INFO,"timeoutflag is" + atleastoneMsgReceived);
                if(atleastoneMsgReceived == false  && getParticipantState() != ParticipantSTATE.WAIT_FOR_VOTE_REQ){
                    logger.log(Level.INFO , "<<<" + config.procNum + ">>> Participant timed out");

                    logger.log(Level.INFO, "Upset before" +upSet.toString() );
                    upSet.remove(new Integer(currCoordinator));
                    dtlog.log("Upset=" + upSet);
                    logger.log(Level.INFO, "Upset after " +upSet.toString() );
                    int smallest = 100;
                    for(Integer i: upSet){
                        if(i.intValue() < smallest){
                            smallest = i.intValue();
                        }
                    }
                    ParseMessage Transaction = getCurrTransaction();
                    if(smallest == config.procNum){

                        Transaction.setSource(String.valueOf(config.procNum));
                        promoteMe(config.procNum,getParticipantState(), Transaction);

                        setParticipantState(ParticipantSTATE.WAIT_FOR_VOTE_REQ);
                    }
                    else{
                        currCoordinator = smallest;
                        logger.log(Level.SEVERE, "<<< " + config.procNum + ">>> Sending UR-Elected to " + currCoordinator);
                        sendURElectedMsg(participantState, Transaction, currCoordinator);
                    }
                }
                ur_elected_count = 0;

                if(getParticipantState().equals(ParticipantSTATE.WAIT_FOR_VOTE_REQ)){
                    /**
                     * Process all the pending Recovery_Req
                     */
                    processRecoveryReq(recovery_req);
                    recovery_req.clear();
                }
             }
        }

        private void sendURElectedMsg(ParticipantSTATE participantState, ParseMessage pmRequest, int destProcNum ){
            pmRequest.setMessageHeader(TransitionMessage.UR_ELECTED.toString());
            String stRequest = pmRequest.serialize();
            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send UR elected to:"+ destProcNum+ "Message:" + stRequest);
            controller.sendMsg(destProcNum, stRequest);
        }

        private void sendVote(ParticipantSTATE tempstate, ParseMessage pmResponse) throws Exception {

            if(tempstate.equals(ParticipantSTATE.UNCERTAIN))
                pmResponse.setMessageHeader(TransitionMessage.YES.toString());
            else if(tempstate.equals(ParticipantSTATE.ABORTED))
                pmResponse.setMessageHeader(TransitionMessage.NO.toString());

            String senderProcNum = pmResponse.getSource(); // Coordinator's Pid
            pmResponse.setSource(String.valueOf(config.procNum)); // Participant's Pid
            String response = pmResponse.serialize();

            logger.log(Level.INFO, "");
            setParticipantState(tempstate);
            //dtlog.log(participantState.toString() + ";");
            dtlog.log("Upset= " + upSet);
            dtlog.log("Response=" + response);
            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send My Vote = " + response);

            controller.sendMsg(Integer.valueOf(senderProcNum), response );

            if(tempstate.equals(ParticipantSTATE.ABORTED)){
                pmResponse.setMessageHeader(TransitionMessage.ABORT.toString());
                dtlog.log("Decision="+ pmResponse.serialize());
                setParticipantState(ParticipantSTATE.WAIT_FOR_VOTE_REQ);
            }
            return;
        }

        private void sendACK(ParticipantSTATE tempstate, ParseMessage pmResponse) throws Exception{
            pmResponse.setMessageHeader(TransitionMessage.ACK.toString());
            String senderProcNum = pmResponse.getSource(); // Coordinator's Pid
            pmResponse.setSource(String.valueOf(config.procNum)); // Participant's Pid
            String response = pmResponse.serialize();

            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send My ACK = " + response);
            setParticipantState(tempstate);
            controller.sendMsg(Integer.valueOf(senderProcNum), response );
            return;
        }


        private void goForCommit(ParticipantSTATE tempstate, ParseMessage pmResponse) throws Exception{

            pmResponse.setSource(String.valueOf(config.procNum));
            playlist.actOnDecision(pmResponse);
            dtlog.log("Decision="+pmResponse.serialize());
            logger.log(Level.INFO , "<<<" + config.procNum + ">>> commit My PLayList = " + pmResponse);

            logger.log(Level.INFO, participantState.toString() + ";");
            setParticipantState(ParticipantSTATE.WAIT_FOR_VOTE_REQ);
            return;
        }

        private void sendParticipantState(ParticipantSTATE state, ParseMessage stResponse){

            String senderProcNum = stResponse.getSource();
            stResponse.setSource(String.valueOf(config.procNum));
            stResponse.setMessageHeader(state.toString());
            String stateResponse = stResponse.serialize();

            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send My State Response = " + stateResponse);

            controller.sendMsg(Integer.valueOf(senderProcNum), stateResponse);
            // Don;t Log your  response to State_request
            return;
        }
    }

    public class Coordinator {

        STATE coordState;
        HashSet<ParseMessage> recovery_req;
        ParseMessage pm;
        public Coordinator() {
            try {
                coordState = null;
                recovery_req = new HashSet<ParseMessage>();
                pm = new ParseMessage();
                //timeout = timeout + timeout/3;


            } catch(Exception e) {

            }
        }

        public void setPm(ParseMessage newPm) {
            this.pm = newPm;
        }

        public void setCoordState(STATE state) {
            coordState = state;
        }

        private  STATE  getCoordinatorState(){
            return coordState;
        }
        private void pollSockCoordinator() {
            logger.log(Level.INFO,"<<<"+ config.procNum + ">>>> Entered pollsock()");
            List<String> input=new ArrayList<String>();
            long smallTimeout = timeout/10;
            //pm = new ParseMessage();

            // TODO check for msgs apart from GO
            try {
                while(true){
                    if(coordState == null && input.isEmpty()){
                        Thread.sleep(timeout);

                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Waiting to initialize 3PC");
                        input = controller.getReceivedMsgs();


                    } else if(coordState == null && !input.isEmpty()) {
                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Initializing 3PC");
                        boolean validReq = false;

                        /**
                         * Reinitializing the upset for an new transaction
                         */
                        upSet = new HashSet<Integer>();
                        for (int i=0; i<config.numProcesses; i++)
                            upSet.add(i);
                        dtlog.log("Upset=" + upSet);
                        //TODO: Has to be removed if getMessagesUntilTimeout is done for the initial msg as well
                        for(String msg: input) {
                            ParseMessage temp = new ParseMessage(msg);
                            if (temp.getSource().equalsIgnoreCase("Client")) {
                                validReq = true;
                                pm = temp;
                                coordState = automata.getStartState(coordState, pm );
                            } else if( temp.getMessageHeader().equalsIgnoreCase(TransitionMessage.RECOVERY_REQ.toString())) {
                                if(!temp.getSource().equals(String.valueOf(config.procNum)))
                                recovery_req.add(temp);
                            }
                        }
                        if(!validReq) {
                            input = new ArrayList<String>();
                        }
                        if(!recovery_req.isEmpty()) {
                            processRecoveryReq(recovery_req);
                        }

                    } else if (coordState.equals(STATE.START_3PC)){
                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Sending Vote-Req");
                        sendVoteRequest(pm);
                        coordState = automata.getCoordinatorNextState(coordState, null, -1);
                    } else if(coordState.equals(STATE.WAIT_FOR_VOTE)) {
                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Waiting for Votes");
                        List<ParseMessage> receivedMessages = getMessagesUntilTimeOut();
                        ParseMessage coordVOTE = new ParseMessage();

                        if(automata.getMyVote(pm, playlist)) {
                            coordVOTE.setSource(String.valueOf(config.procNum));
                            coordVOTE.setMessageHeader("Yes");
                        } else {
                            coordVOTE.setSource(String.valueOf(config.procNum));
                            coordVOTE.setMessageHeader("No");
                        }

                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> My Vote"+ coordVOTE.getMessageHeader());
                        receivedMessages.add(coordVOTE);

                        coordState = automata.getCoordinatorNextState(coordState, receivedMessages, config.numProcesses);
                        updateUpset(receivedMessages);
                    } else if(coordState.equals(STATE.SEND_PRECOMMIT)) {
                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Sending Pre-commits");
                        sendPreCommit(pm);
                        coordState = automata.getCoordinatorNextState(coordState, null, -1);
                    } else if(coordState.equals(STATE.WAIT_FOR_ACKS)) {
                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Waiting for Acks");
                        List<ParseMessage> receivedMessages = getMessagesUntilTimeOut();
                        coordState = automata.getCoordinatorNextState(coordState, receivedMessages, upSet.size()-1);
                    } else if(coordState.equals(STATE.COMMIT)) {
                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Sending Commit to everyone");
                        logger.log(Level.INFO, "value of PM before sending commit " + pm.serialize());
                        sendCommit(pm);
                        coordState=null;
                        input = new ArrayList<String>();

                        /**
                         * Adding decision to the HashMap
                         */
                         old_decisions.put(pm.getTransaction(), TransitionMessage.COMMIT);

                        /**
                         * Process all the pending Recovery_Req
                         */
                        processRecoveryReq(recovery_req);
                        recovery_req.clear();

                    } else if(coordState.equals(STATE.ABORT)) {

                        //TODO Send abort only to ppl who voted yes
                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Sending Abort to everyone");
                        logger.log(Level.INFO, "<<<" + config.procNum + ">>> Sending Abort to everyone with message " + pm.serialize());
                        sendAbort(pm);
                        coordState = null;
                        input = new ArrayList<String>();

                        /**
                         * Adding decision to the HashMap
                         */
                        old_decisions.put(pm.getTransaction(), TransitionMessage.ABORT);

                        /**
                         * Process all the pending Recovery_Req
                         */
                        processRecoveryReq(recovery_req);
                        recovery_req.clear();

                    } else {
                        //INVALID ---> Throw exception
                    }
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, " Exception occured " + e.getMessage());
                e.printStackTrace();
            }
        }

        private void sendVoteRequest(ParseMessage pmrequest) throws Exception {

            pmrequest.setMessageHeader(TransitionMessage.VOTE_REQ.toString());
            pmrequest.setSource(String.valueOf(config.procNum));
            String request = pmrequest.serialize();

            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send Vote-Req = " + request);
//            dtlog.log(coordState.toString() + ";");
            dtlog.log("Request=" + request);

            //TODO Change if liveSet of processes in implemented
            for(int i=0; i<config.numProcesses ;i++) {
                if(config.procNum == i)
                    continue;
                controller.sendMsg(i, request);
            }
//            dtlog.log(coordState.toString() + ";");
//            dtlog.log("Request=" + request);
        }

        protected void sendPreCommit(ParseMessage pmrequest) throws Exception {
            //TODO Write Precommit to log
            pmrequest.setMessageHeader(TransitionMessage.PRECOMMIT.toString());
            pmrequest.setSource(String.valueOf(config.procNum));
            String request = pmrequest.serialize();

            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send Precommit = " + request);
            dtlog.log("Precommit=" + request);

            for(int i=0; i<config.numProcesses ;i++) {
                if(config.procNum == i)
                    continue;
                controller.sendMsg(i, request );

                if(precommitCount == i)
                   System.exit(1);
            }
        }

        protected  void sendCommit(ParseMessage pmrequest) throws Exception {
            //TODO write to log
            pmrequest.setMessageHeader(TransitionMessage.COMMIT.toString());
            pmrequest.setSource(String.valueOf(config.procNum));
            String request = pmrequest.serialize();

            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send Decision = " + request);
//            dtlog.log(coordState.toString() + ";");
            dtlog.log("Decision=" + request);

            for(int i=0; i<config.numProcesses ;i++) {
                if(config.procNum == i)
                    continue;
                controller.sendMsg(i, request );

                if(commitCount == i)
                    System.exit(1);
            }

            playlist.actOnDecision(pmrequest);
        }

        protected void sendAbort(ParseMessage pmrequest) throws Exception {
            pmrequest.setMessageHeader(TransitionMessage.ABORT.toString());
            pmrequest.setSource(String.valueOf(config.procNum));
            String request = pmrequest.serialize();

            logger.log(Level.INFO , "<<<" + config.procNum + ">>> Send Decision = " + request);
//            dtlog.log(coordState.toString() + ";");
            dtlog.log("Decision=" + request + "\n");

            //TODO Send only to ppl who voted yes
            for(int i=0; i<config.numProcesses ;i++) {
                if(config.procNum == i)
                    continue;
                controller.sendMsg(i, request );
            }
        }

        protected List<ParseMessage> getMessagesUntilTimeOut() throws Exception {
            List<String> messages = new ArrayList<String>();
             List<ParseMessage> pmTemp = new ArrayList<ParseMessage>();
            long startTime = (System.currentTimeMillis()+timeout);
            long smallTimeout = timeout/10;
            TransitionMessage nextHeader = automata.getCoordinatorNextMessageHeader(coordState);
            boolean timeout_flag=false;
            boolean vote_flag = false;

            while(System.currentTimeMillis() < startTime) {
                Thread.sleep(smallTimeout);
                messages = (controller.getReceivedMsgs());

                for(String m:messages) {
                    logger.log(Level.INFO , "<<<" + config.procNum + ">> " + "Number of messages "+ messages.size());
                    ParseMessage nextreceivedPM = new ParseMessage(m);
//                    if(nextHeader.equals(TransitionMessage.VOTE))
//                    {
                        if(nextreceivedPM.getMessageHeader().equalsIgnoreCase(TransitionMessage.YES.toString()) ||
                                nextreceivedPM.getMessageHeader().equalsIgnoreCase(TransitionMessage.NO.toString()))
                        {
                            vote_flag = true;
                            pmTemp.add(nextreceivedPM);
                            logger.log(Level.INFO , "<<<" + config.procNum + ">> Vote from [" + nextreceivedPM.getSource() +
                                    "] vote as " + nextreceivedPM.getMessageHeader() + "--->" + messages.size());
                        }
                        // else ignore the message.
                        // garbage
//                    }
                    // To cache all the Recovery_Req messages
                    else if(nextreceivedPM.getMessageHeader().equalsIgnoreCase(TransitionMessage.RECOVERY_REQ.toString())) {
                       if(!nextreceivedPM.getSource().equals(String.valueOf(config.procNum)))
                        recovery_req.add(nextreceivedPM);
                    }
                    //For all other types of transition messages.
                    else if(nextreceivedPM.getMessageHeader().equalsIgnoreCase(nextHeader.toString())){
                        pmTemp.add(nextreceivedPM);
                    }
                    /**
                     * DEATH AFTER / deterministic failure
                     */
                    if(deathAfter_dest == config.procNum){
                        deathAfter(nextreceivedPM);
                    }
                }

                //Makes sure that only valid messages make it to the pmTemp list
                if(pmTemp.size() == config.numProcesses-1) {
                    timeout_flag = true;
                    break;
                }

            }
            //TODO Update upset

//            if(vote_flag)
//                updateUpset(pmTemp);

            if(!timeout_flag){
                logger.log(Level.INFO, "<<<" + config.procNum + ">>> Someone might have timed out");
            }
            return pmTemp;
        }
    }

    private void updateUpset(List<ParseMessage> replies) throws Exception {
        HashSet<Integer> updateUpset = new HashSet<Integer>();

        updateUpset.add(config.procNum);
        for(ParseMessage item: replies) {
            if(upSet.contains(Integer.parseInt(item.getSource()))) {
                updateUpset.add(Integer.parseInt(item.getSource()));
            }
        }
        if(!upSet.equals(updateUpset))
        {
            logger.log(Level.SEVERE, "Updating the stupid upset");
            upSet = updateUpset;
            dtlog.log("Upset=" + upSet.toString());
        }
    }
}
