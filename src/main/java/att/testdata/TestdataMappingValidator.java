package att.testdata;

import java.util.LinkedHashMap;
import java.util.Map;

/** Validates mapped record paths and interpolation types before execution starts. */
public final class TestdataMappingValidator {
    private TestdataMappingValidator() { }

    public static void validate(Map<String, Object> mapping, TestdataRegistry registry) throws Exception {
        Map<String, TestdataDescriptor> descriptors = new LinkedHashMap<String, TestdataDescriptor>();
        for (TestdataSyntax.Reference reference : TestdataSyntax.referenceExpressions(mapping)) {
            TestdataDescriptor descriptor = descriptors.get(reference.id());
            if (descriptor == null) {
                descriptor = registry.resolve(reference.id());
                descriptors.put(reference.id(), descriptor);
            }
            long count = descriptor.count();
            if (descriptor.generated()) {
                validateRecord(descriptor.record(0), reference);
                if (count > 1) validateRecord(descriptor.record(count - 1), reference);
            } else {
                for (long index = 0; index < count; index++)
                    validateRecord(descriptor.record(index), reference);
            }
        }
    }

    private static void validateRecord(Object record, TestdataSyntax.Reference reference) {
        Object value = TestdataInputResolver.traverse(record, reference.path());
        if (reference.embedded() && (value == null || value instanceof Map || value instanceof Iterable
                || value.getClass().isArray()))
            throw new IllegalArgumentException("Embedded input mapping references must resolve to a non-null scalar: @{"
                    + reference.id() + (reference.path().isEmpty() ? "" : "." + reference.path()) + "}");
    }
}
