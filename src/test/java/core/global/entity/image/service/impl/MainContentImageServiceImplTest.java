package core.global.entity.image.service.impl;

import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageOperationBatchService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.enums.PollType;
import core.global.enums.common.ImageOperationOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MainContentImageServiceImplTest {

    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageStorageClient storageClient;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ImageOperationBatchService imageOperationBatchService;

    @InjectMocks
    private MainContentImageServiceImpl imageService;

    private ImageOperationBatchService.TrackedCopy trackedCopy;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(imageService, "bucket", "bucket");
        ReflectionTestUtils.setField(imageService, "endPoint", "https://object.example.com");
        ReflectionTestUtils.setField(imageService, "cdnBaseUrl", "https://cdn.example.com");
        lenient().when(storageClient.isStagingKey("temp/a.jpg")).thenReturn(true);
        trackedCopy = new ImageOperationBatchService.TrackedCopy(
                UUID.randomUUID(), "temp/a.jpg", "vote/20/000_a.jpg"
        );
    }

    @Test
    void upsertPollImagesTracksCopyAndSchedulesCleanup() {
        when(imageOperationBatchService.copy(any(), any(), eq(20L), eq("temp/a.jpg"), eq("vote/20/000_a.jpg")))
                .thenReturn(trackedCopy);
        when(imageRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        imageService.upsertPollImages(20L, List.of("temp/a.jpg"), List.of(), PollType.VOTE);

        verify(imageOperationBatchService).registerRollbackCompensation(List.of(trackedCopy));
        verify(imageRepository).flush();
        verify(imageOperationBatchService).scheduleCleanup(
                ImageOperationOwnerType.POLL, 20L, List.of(trackedCopy), List.of()
        );
        verify(storageClient, never()).deleteObjectsBulk(anyList());
    }

    @Test
    void upsertPollImagesCompensatesWhenDbFlushFails() {
        when(imageOperationBatchService.copy(any(), any(), eq(20L), anyString(), anyString()))
                .thenReturn(trackedCopy);
        when(imageRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new DataAccessResourceFailureException("db down")).when(imageRepository).flush();

        assertThatThrownBy(() -> imageService.upsertPollImages(
                20L, List.of("temp/a.jpg"), List.of(), PollType.VOTE
        )).isInstanceOf(DataAccessResourceFailureException.class);

        verify(imageOperationBatchService).compensate(List.of(trackedCopy));
        verify(imageOperationBatchService, never()).scheduleCleanup(any(), anyLong(), anyList(), anyList());
    }

}
