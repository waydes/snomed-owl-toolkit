package org.snomed.otf.owltoolkit.conversion;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.ihtsdo.otf.snomedboot.factory.ImpotentComponentFactory;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.constants.Concepts;
import org.snomed.otf.owltoolkit.constants.RF2Headers;
import org.snomed.otf.owltoolkit.taxonomy.SnomedTaxonomy;
import org.snomed.otf.owltoolkit.util.InputStreamSet;
import org.snomed.otf.owltoolkit.util.OptionalFileInputStream;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Used to convert extension stated relationships having international concepts as source to 
 * primitive additional OWL Axiom reference set.
 * The following cases found in the US extension:
 * 1. Adding just Is-A
 * 2. Adding Is-A and not grouped (group 0) attributes
 * 3. Adding not grouped non Is-A only
 * 4. Adding grouped attribute (non zero role group)
 * 
 * Replacing above with additional axioms:
 *  I. Create primitive additional axioms for Case 1 and 2 in the extension module
 *  II. Case 3 requires adding an Is_A + existing not grouped Is-A to create primitive additional axiom in the extension module only.
 *  III. Case 4 requires the international stated view to create additional axiom.
 *
 * Note: Axioms must be primitive when created for I and II
 *
 */
public class AdditionalExtensionStatedRelationshipToOwlRefsetService extends StatedRelationshipToOwlRefsetService {

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public void convertExtensionStatedRelationshipsToAdditionalAxioms(InputStreamSet snapshotInputStreamSet,
			OptionalFileInputStream deltaStream,
			OutputStream rf2DeltaZipResults,
			String effectiveDate) throws ConversionException, OWLOntologyCreationException, IOException {
		
		try (ZipOutputStream zipOutputStream = new ZipOutputStream(rf2DeltaZipResults)) {
			zipOutputStream.putNextEntry(new ZipEntry(SCT2_STATED_RELATIONSHIP_DELTA + effectiveDate + TXT));
			ExtensionModifyingInternationalExtractor processor = new ExtensionModifyingInternationalExtractor(zipOutputStream);
			SnomedTaxonomy snomedTaxonomy = readSnomedTaxonomy(snapshotInputStreamSet, deltaStream, processor, null);
			processor.complete();
			zipOutputStream.closeEntry();
			logger.info("Total active stated relationships having international source and destination concepts:" + processor.getTotalSourceAndDestinationAreIntConcepts());
			logger.info("Total active stated relationships having international source concept only:" + processor.getTotalActiveStatedRelationshipWithIntSource());
			Set<Long> intConceptsModified = processor.getInternationalConceptsModifiedByExtension();
			logger.info("Total active internationl source concepts only modifed by extension:" + intConceptsModified.size());
			
			Set<Long> bothConcepts = processor.getConceptsHavingBoth();
			logger.info("Total active internationl concepts modifed by extension:" + bothConcepts.size());
			for (Long concept : bothConcepts) {
				if (intConceptsModified.contains(concept)) {
					System.out.println("Concept has matched two cases:" + concept);
				}
			}
			boolean generateAxiomsWithoutInt = true;
			Set<Long> conceptsOnlyAddedIsA = new HashSet<>(processor.getConceptsHavingIsARelationship());
			conceptsOnlyAddedIsA.removeAll(processor.getConceptsHavingNonIsARelationship());
			logger.info("Total concepts only added IS-A:" + conceptsOnlyAddedIsA.size());
			Set<Long> conceptsHavingBoth = new HashSet<>(processor.getConceptsHavingIsARelationship());
			conceptsHavingBoth.retainAll(processor.getConceptsHavingNonIsARelationship());
			logger.info("Total concepts having an Is-A and non IS-A:" + conceptsHavingBoth.size());
			Set<Long> requiringIntModel = new HashSet<>();
			Set<Long> withoutIntModel = new HashSet<>();
			for (Long concept : conceptsHavingBoth) {
				boolean grouped = false;
				for (String roleGroup : processor.getNonIsARelationshipGroupMap().get(concept)) {
					if (!"0".equals(roleGroup)) {
						grouped = true;
						break;
					}
				}
				if (grouped) {
					requiringIntModel.add(concept);
				} else {
					withoutIntModel.add(concept);
				}
			}
			Set<Long> conceptsHavingNonIsaOnly =  new HashSet<>(processor.getConceptsHavingNonIsARelationship());
			conceptsHavingNonIsaOnly.removeAll(processor.getConceptsHavingIsARelationship());
			logger.info("Total concepts having non IS-A only:" + conceptsHavingNonIsaOnly.size());
			logger.info("Total concepts without the need of INT model:" + withoutIntModel.size());
			Set<Long> toAddIsAOnly = new HashSet<>();
			for (Long concept : conceptsHavingNonIsaOnly) {
				boolean grouped = false;
				for (String roleGroup : processor.getNonIsARelationshipGroupMap().get(concept)) {
					if (!"0".equals(roleGroup)) {
						grouped = true;
						break;
					}
				}
				if (!grouped) {
					toAddIsAOnly.add(concept);
				} else {
					requiringIntModel.add(concept);
				}
			}
			
			logger.info("Total concepts requiring the international modelling " + requiringIntModel.size());
			logger.info("Total concepts need to add IS_A only " + toAddIsAOnly);
			zipOutputStream.putNextEntry(new ZipEntry(OWL_AXIOM_REFSET_DELTA + effectiveDate + TXT));
			Set<Long> conceptsToGenerate = new HashSet<>();
			if (generateAxiomsWithoutInt) {
				conceptsToGenerate.addAll(withoutIntModel);
				conceptsToGenerate.addAll(conceptsOnlyAddedIsA);
				logger.info("Concepts without the need of INT model:" + conceptsToGenerate);
			} else {
				conceptsToGenerate.addAll(requiringIntModel);
			}
			//adding is a only
			conceptsToGenerate = toAddIsAOnly;
			convertStatedRelationshipsToOwlRefsetForExtension(snomedTaxonomy, conceptsToGenerate, zipOutputStream, processor.getExtensionModuleId());
			zipOutputStream.closeEntry();
		}
	}
	
	private static class ExtensionModifyingInternationalExtractor extends ImpotentComponentFactory {
		
		private static final String IS_A = "116680003";
		private Set<Long> activeInternationalConcepts = new LongOpenHashSet();
		private String extensionModuleId = null;
		private Set<Long> sourceConcepts = new LongOpenHashSet();
		private Set<Long> sourceAndDestinatinConcepts = new LongOpenHashSet();
		private int totalActiveStatedRelationshipWithIntSource = 0;
		private int totalSourceAndDestinationAreIntConcepts = 0;
		private final BufferedWriter writer;
		private final List<IOException> exceptionsThrown = new ArrayList<>();
		private Set<Long> hasIsAConcepts = new LongOpenHashSet();
		private Set<Long> hasNonIsAConcepts = new LongOpenHashSet();
		private Map<Long, Set<String>> nonIsaRelationGroupMap = new HashMap<Long, Set<String>>();
		
		public ExtensionModifyingInternationalExtractor(ZipOutputStream zipOutputStream) throws IOException {
			writer = new BufferedWriter(new OutputStreamWriter(zipOutputStream));
			writer.write(RF2Headers.RELATIONSHIP_HEADER);
			writer.newLine();
		}
		
		@Override
		public void newConceptState(String conceptId, String effectiveTime, String active, String moduleId, String definitionStatusId) {
			if ("1".equals(active)) {
				if ("900000000000012004".equals(moduleId) || "900000000000207008".equals(moduleId)) {
					activeInternationalConcepts.add(Long.valueOf(conceptId));
				} else {
					if (extensionModuleId == null) {
						extensionModuleId = moduleId;
					}
				}
			}
		}
		
		@Override
		public void newRelationshipState(String id, String effectiveTime, String active, String moduleId, String sourceId, String destinationId, String relationshipGroup, String typeId, String characteristicTypeId, String modifierId) {
			if (active.equals("1") && characteristicTypeId.equals(Concepts.STATED_RELATIONSHIP)) {
				if (extensionModuleId.equals(moduleId)) {
					if (activeInternationalConcepts.contains(Long.valueOf(sourceId))) {
						if (activeInternationalConcepts.contains(Long.valueOf(destinationId))) {
							sourceAndDestinatinConcepts.add(Long.valueOf(sourceId));
							totalSourceAndDestinationAreIntConcepts++;
							if (typeId.equals(IS_A)) {
								hasIsAConcepts.add(Long.valueOf(sourceId));
							} else {
								hasNonIsAConcepts.add(Long.valueOf(sourceId));
								nonIsaRelationGroupMap.computeIfAbsent(Long.valueOf(sourceId), k -> new HashSet<String>()).add(relationshipGroup);
							}
							try {
								writeStateRelationshipRow(writer, id, "0", moduleId, sourceId, destinationId, relationshipGroup, typeId);
							} catch (IOException e) {
								exceptionsThrown.add(e);
							}
							
						} else {
							sourceConcepts.add(Long.valueOf(sourceId));
							totalActiveStatedRelationshipWithIntSource++;
						}
					}
				}
			}
		}
		
		public void complete() throws IOException {
			writer.flush();
			if (!exceptionsThrown.isEmpty()) {
				throw exceptionsThrown.get(0);
			}
		}
		
		public Set<Long> getInternationalConceptsModifiedByExtension() {
			return this.sourceConcepts;
		}

		public String getExtensionModuleId() {
			return this.extensionModuleId;
		}
		
		public int getTotalActiveStatedRelationshipWithIntSource() {
			return this.totalActiveStatedRelationshipWithIntSource;
		}
		
		public int getTotalSourceAndDestinationAreIntConcepts() {
			return this.totalSourceAndDestinationAreIntConcepts;
		}
		
		public Set<Long> getConceptsHavingBoth() {
			return this.sourceAndDestinatinConcepts;
		}
		
		public Set<Long> getConceptsHavingIsARelationship() {
			return this.hasIsAConcepts;
		}
		
		public Set<Long> getConceptsHavingNonIsARelationship() {
			return this.hasNonIsAConcepts;
		}
		
		public Map<Long, Set<String>> getNonIsARelationshipGroupMap() {
			return this.nonIsaRelationGroupMap;
		}
	}
}
