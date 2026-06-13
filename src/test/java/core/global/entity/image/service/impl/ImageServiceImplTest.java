package core.global.entity.image.service.impl;

import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.ImagePersistenceTransactionService;
import core.global.entity.image.service.PostImageOperationPipelineService;
import core.global.entity.image.service.PostImageService;
import core.global.entity.image.service.ProfileImageService;
import core.global.enums.PollType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ImageServiceImplTest {

    @Mock
    private PostImageService postImageService;
    @Mock
    private ProfileImageService profileImageService;
    @Mock
    private ImageStorageClient imageStorageClient;
    @Mock
    private MainContentImageService mainContentImageService;
    @Mock
    private PostImageOperationPipelineService postImageOperationPipelineService;
    @Mock
    private ImagePersistenceTransactionService imagePersistenceTransactionService;

    @InjectMocks
    private ImageServiceImpl imageService;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void updatePostImagesRunsImmediatelyWithoutTransactionSynchronization() {
        imageService.updatePostImages(10L, List.of("temp/a.jpg"), List.of("posts/10/old.jpg"));

        verify(postImageService).updatePostImages(
                10L,
                List.of("temp/a.jpg"),
                List.of("posts/10/old.jpg")
        );
    }

    @Test
    void updatePostImagesRunsOnlyAfterCommitAndUsesScheduledValues() {
        TransactionSynchronizationManager.initSynchronization();
        List<String> adds = new ArrayList<>(List.of("temp/a.jpg"));
        List<String> removes = new ArrayList<>(List.of("posts/10/old.jpg"));

        imageService.updatePostImages(10L, adds, removes);
        adds.add("temp/late.jpg");
        removes.clear();

        verify(postImageService, never()).updatePostImages(10L, adds, removes);
        triggerAfterCommit();

        verify(postImageService).updatePostImages(
                10L,
                List.of("temp/a.jpg"),
                List.of("posts/10/old.jpg")
        );
    }

    @Test
    void updatePostImagesRegistersFinalKeyInsideCurrentTransaction() {
        imageService.updatePostImages(
                10L,
                List.of("posts/objects/new.jpg"),
                List.of("posts/10/old.jpg")
        );

        verify(imagePersistenceTransactionService).updateFinalPostImages(
                10L, List.of("posts/objects/new.jpg"), List.of("posts/10/old.jpg")
        );
        verify(postImageService, never()).updatePostImages(
                10L, List.of("posts/objects/new.jpg"), List.of("posts/10/old.jpg")
        );
    }

    @Test
    void savePostImagesDoesNotRunAfterRollback() {
        TransactionSynchronizationManager.initSynchronization();

        imageService.savePostImages(10L, List.of("temp/a.jpg"));
        triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(postImageService, never()).savePostImages(10L, List.of("temp/a.jpg"));
    }

    @Test
    void savePostImagesSchedulesDurablePipelineWhenRabbitIsEnabled() {
        ReflectionTestUtils.setField(imageService, "imageOperationRabbitEnabled", true);

        imageService.savePostImages(10L, List.of("temp/a.jpg"));

        verify(postImageOperationPipelineService).scheduleCreate(10L, List.of("temp/a.jpg"));
        verify(postImageService, never()).savePostImages(10L, List.of("temp/a.jpg"));
    }

    @Test
    void savePostImagesRegistersFinalKeysInsideCurrentTransaction() {
        imageService.savePostImages(10L, List.of("posts/objects/new.jpg"));

        verify(imagePersistenceTransactionService).saveFinalPostImages(
                10L, List.of("posts/objects/new.jpg")
        );
        verify(postImageOperationPipelineService, never()).scheduleCreate(
                10L, List.of("posts/objects/new.jpg")
        );
    }

    @Test
    void deletePostImagesDelegatesToTransactionalPersistenceService() {
        imageService.deletePostImages(10L);

        verify(imagePersistenceTransactionService).deletePostImages(10L);
        verify(imageStorageClient, never()).deleteFolder("posts/10");
    }

    @Test
    void upsertPollImagesRunsAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        imageService.upsertPollImages(20L, List.of("temp/a.jpg"), List.of(), PollType.VOTE);
        verify(mainContentImageService, never()).upsertPollImages(20L, List.of("temp/a.jpg"), List.of(), PollType.VOTE);

        triggerAfterCommit();

        verify(mainContentImageService).upsertPollImages(20L, List.of("temp/a.jpg"), List.of(), PollType.VOTE);
    }

    private void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    private void triggerAfterCompletion(int status) {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(status));
    }
}
