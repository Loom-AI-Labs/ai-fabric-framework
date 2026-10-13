package ai.fabric.chat.channel;

/** Provider-neutral outbound adapter selected by the host deployment. */
public interface ConversationChannelGateway {

    ConversationChannelDeliveryResult dispatch(ConversationChannelDispatch dispatch);
}
