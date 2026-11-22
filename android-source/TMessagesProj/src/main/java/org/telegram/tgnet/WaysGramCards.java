package org.telegram.tgnet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.tl.TL_update;

import java.nio.ByteBuffer;

/** Structured WaysGram attachments mapped to the native poll and checklist widgets. */
final class WaysGramCards {
    private WaysGramCards() { }

    static JSONObject encode(TLRPC.InputMedia input) throws WaysGramApi.ApiException {
        JSONObject card = new JSONObject();
        JSONArray items = new JSONArray();
        try {
            if (input instanceof TLRPC.TL_inputMediaPoll) {
                TLRPC.TL_inputMediaPoll media = (TLRPC.TL_inputMediaPoll) input;
                TLRPC.Poll poll = media.poll;
                if (poll == null || poll.quiz || poll.public_voters || poll.closed || poll.close_date != 0 || poll.close_period != 0
                        || poll.open_answers || poll.hide_results_until_close || poll.revoting_disabled || poll.shuffle_answers
                        || poll.subscribers_only || !poll.countries_iso2.isEmpty() || media.attached_media != null || media.solution_media != null) {
                    throw new WaysGramApi.ApiException(400, "POLL_OPTIONS_NOT_SUPPORTED");
                }
                card.put("kind", "poll").put("title", poll.question.text).put("multiple_choice", poll.multiple_choice);
                for (int i = 0; i < poll.answers.size(); i++) {
                    TLRPC.PollAnswer item = poll.answers.get(i);
                    if (item.input_media != null || item.media != null) throw new WaysGramApi.ApiException(400, "POLL_OPTIONS_NOT_SUPPORTED");
                    items.put(new JSONObject().put("id", i).put("text", item.text.text));
                }
            } else {
                TLRPC.TodoList todo = ((TLRPC.TL_inputMediaTodo) input).todo;
                if (todo == null) throw new WaysGramApi.ApiException(400, "CHECKLIST_INVALID");
                card.put("kind", "checklist").put("title", todo.title.text).put("others_can_complete", todo.others_can_complete);
                for (TLRPC.TodoItem item : todo.list) {
                    items.put(new JSONObject().put("id", item.id).put("text", item.title.text));
                }
            }
            return card.put("items", items);
        } catch (JSONException e) {
            throw new WaysGramApi.ApiException(400, "CARD_INVALID");
        }
    }

    private static TLRPC.TL_textWithEntities text(String value) {
        TLRPC.TL_textWithEntities result = new TLRPC.TL_textWithEntities();
        result.text = value;
        return result;
    }

    static boolean attach(TLRPC.Message message, JSONObject card, int serverId) {
        if (card == null) return false;
        JSONArray items = card.optJSONArray("items");
        if (items == null) return false;
        if ("poll".equals(card.optString("kind"))) {
            TLRPC.TL_messageMediaPoll media = new TLRPC.TL_messageMediaPoll();
            TLRPC.TL_poll poll = new TLRPC.TL_poll();
            long scope = message.peer_id instanceof TLRPC.TL_peerChat ? 1 : message.peer_id instanceof TLRPC.TL_peerChannel ? 2 : 0;
            poll.id = (scope << 32) | Integer.toUnsignedLong(serverId);
            poll.question = text(card.optString("title"));
            poll.multiple_choice = card.optBoolean("multiple_choice");
            TLRPC.TL_pollResults results = new TLRPC.TL_pollResults();
            results.flags = 2 | 4;
            results.total_voters = card.optInt("voters");
            JSONObject votes = card.optJSONObject("votes");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                int id = item.optInt("id");
                TLRPC.TL_pollAnswer answer = new TLRPC.TL_pollAnswer();
                answer.text = text(item.optString("text"));
                answer.option = ByteBuffer.allocate(4).putInt(id).array();
                poll.answers.add(answer);
                JSONObject vote = votes == null ? null : votes.optJSONObject(Integer.toString(id));
                TLRPC.TL_pollAnswerVoters voters = new TLRPC.TL_pollAnswerVoters();
                voters.option = answer.option;
                voters.flags = 4;
                voters.voters = vote == null ? 0 : vote.optInt("count");
                voters.chosen = vote != null && vote.optBoolean("chosen");
                results.results.add(voters);
            }
            media.poll = poll;
            media.results = results;
            message.media = media;
        } else if ("checklist".equals(card.optString("kind"))) {
            TLRPC.TL_messageMediaToDo media = new TLRPC.TL_messageMediaToDo();
            media.todo = new TLRPC.TodoList();
            media.todo.title = text(card.optString("title"));
            media.todo.others_can_complete = card.optBoolean("others_can_complete");
            JSONObject done = card.optJSONObject("done");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                TLRPC.TodoItem entry = new TLRPC.TodoItem();
                entry.id = item.optInt("id");
                entry.title = text(item.optString("text"));
                media.todo.list.add(entry);
                JSONObject state = done == null ? null : done.optJSONObject(Integer.toString(entry.id));
                if (state != null) {
                    TLRPC.TL_todoCompletion completion = new TLRPC.TL_todoCompletion();
                    completion.id = entry.id;
                    completion.completed_by = WaysGramMapper.peerUser(state.optLong("user"));
                    completion.date = state.optInt("date");
                    media.completions.add(completion);
                    media.flags |= 1;
                }
            }
            message.media = media;
        } else return false;
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        message.message = "";
        return true;
    }

    static TLRPC.Update update(TLRPC.Message message) {
        if (message.media instanceof TLRPC.TL_messageMediaPoll) {
            TLRPC.TL_messageMediaPoll media = (TLRPC.TL_messageMediaPoll) message.media;
            TL_update.TL_updateMessagePoll update = new TL_update.TL_updateMessagePoll();
            update.flags = 3;
            update.poll_id = media.poll.id;
            update.poll = media.poll;
            update.results = media.results;
            update.peer = message.peer_id;
            update.msg_id = message.id;
            return update;
        }
        if (message.peer_id instanceof TLRPC.TL_peerChannel) {
            TL_update.TL_updateEditChannelMessage update = new TL_update.TL_updateEditChannelMessage();
            update.message = message;
            return update;
        }
        TL_update.TL_updateEditMessage update = new TL_update.TL_updateEditMessage();
        update.message = message;
        return update;
    }
}
