package pl.wp.crashmanagementserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class PublishResource {
	@Autowired
	private SnapshotGenerator snapshotGenerator;

	@RequestMapping(path = "/triggerChange", method = RequestMethod.POST)
	void triggerChange() {
		snapshotGenerator.triggerChange();
	}
}
