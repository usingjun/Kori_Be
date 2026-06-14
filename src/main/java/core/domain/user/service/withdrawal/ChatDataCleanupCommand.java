package core.domain.user.service.withdrawal;

import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ChatDataCleanupCommand implements UserWithdrawalCommand {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Override
    public int order() {
        return UserWithdrawalCommandOrder.CHAT_DATA;
    }

    @Override
    public void execute(UserWithdrawalContext context) {
        Long userId = context.userId();
        List<ChatRoom> ownedChatRooms = chatRoomRepository.findAllByOwnerId(userId);

        for (ChatRoom chatRoom : ownedChatRooms) {
            List<ChatParticipant> participants =
                    chatParticipantRepository.findAllByChatRoomIdAndUserIdNot(chatRoom.getId(), userId);
            if (participants.isEmpty()) {
                chatRoomRepository.delete(chatRoom);
                continue;
            }
            chatRoom.changeOwner(participants.get(0).getUser());
            chatRoomRepository.save(chatRoom);
        }

        chatParticipantRepository.deleteAllByUserId(userId);
        chatMessageRepository.deleteAllBySenderId(userId);
    }
}
