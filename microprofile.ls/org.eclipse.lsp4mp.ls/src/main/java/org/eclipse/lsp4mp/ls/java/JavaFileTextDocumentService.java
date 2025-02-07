/*******************************************************************************
* Copyright (c) 2019-2020 Red Hat Inc. and others.
*
* This program and the accompanying materials are made available under the
* terms of the Eclipse Public License v. 2.0 which is available at
* http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
* which is available at https://www.apache.org/licenses/LICENSE-2.0.
*
* SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
*
* Contributors:
*     Red Hat Inc. - initial API and implementation
*******************************************************************************/
package org.eclipse.lsp4mp.ls.java;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4mp.commons.DocumentFormat;
import org.eclipse.lsp4mp.commons.MicroProfileJavaCodeActionParams;
import org.eclipse.lsp4mp.commons.MicroProfileJavaCodeLensParams;
import org.eclipse.lsp4mp.commons.MicroProfileJavaCompletionParams;
import org.eclipse.lsp4mp.commons.MicroProfileJavaDefinitionParams;
import org.eclipse.lsp4mp.commons.MicroProfileJavaDiagnosticsParams;
import org.eclipse.lsp4mp.commons.MicroProfileJavaDiagnosticsSettings;
import org.eclipse.lsp4mp.commons.MicroProfileJavaHoverParams;
import org.eclipse.lsp4mp.commons.MicroProfilePropertiesChangeEvent;
import org.eclipse.lsp4mp.commons.MicroProfilePropertiesScope;
import org.eclipse.lsp4mp.ls.AbstractTextDocumentService;
import org.eclipse.lsp4mp.ls.MicroProfileLanguageServer;
import org.eclipse.lsp4mp.ls.commons.BadLocationException;
import org.eclipse.lsp4mp.ls.commons.TextDocument;
import org.eclipse.lsp4mp.ls.commons.ValidatorDelayer;
import org.eclipse.lsp4mp.ls.commons.client.CommandKind;
import org.eclipse.lsp4mp.ls.commons.client.ExtendedCompletionCapabilities;
import org.eclipse.lsp4mp.ls.java.JavaTextDocuments.JavaTextDocument;
import org.eclipse.lsp4mp.ls.properties.IPropertiesModelProvider;
import org.eclipse.lsp4mp.model.Node;
import org.eclipse.lsp4mp.model.PropertiesModel;
import org.eclipse.lsp4mp.model.Property;
import org.eclipse.lsp4mp.settings.MicroProfileCodeLensSettings;
import org.eclipse.lsp4mp.settings.MicroProfileValidationSettings;
import org.eclipse.lsp4mp.settings.SharedSettings;
import org.eclipse.lsp4mp.snippets.SnippetContextForJava;
import org.eclipse.lsp4mp.utils.PositionUtils;
import org.eclipse.lsp4mp.utils.PropertiesFileUtils;

/**
 * LSP text document service for Java file.
 *
 * @author Angelo ZERR
 *
 */
public class JavaFileTextDocumentService extends AbstractTextDocumentService {

	private static final Logger LOGGER = Logger.getLogger(JavaFileTextDocumentService.class.getName());

	private final IPropertiesModelProvider propertiesModelProvider;
	private final JavaTextDocuments documents;
	private ValidatorDelayer<JavaTextDocument> validatorDelayer;

	public JavaFileTextDocumentService(MicroProfileLanguageServer microprofileLanguageServer,
			IPropertiesModelProvider propertiesModelProvider, SharedSettings sharedSettings) {
		super(microprofileLanguageServer, sharedSettings);
		this.propertiesModelProvider = propertiesModelProvider;
		this.documents = new JavaTextDocuments(microprofileLanguageServer, microprofileLanguageServer);
		this.validatorDelayer = new ValidatorDelayer<>((javaTextDocument) -> {
			triggerValidationFor(javaTextDocument);
		});
	}

	// ------------------------------ did* for Java file -------------------------

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		validate(documents.onDidOpenTextDocument(params), false);
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		validate(documents.onDidChangeTextDocument(params), true);
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		documents.onDidCloseTextDocument(params);
		String uri = params.getTextDocument().getUri();
		microprofileLanguageServer.getLanguageClient()
				.publishDiagnostics(new PublishDiagnosticsParams(uri, new ArrayList<Diagnostic>()));
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
		// validate all opened java files which belong to a MicroProfile project
		triggerValidationForAll(null);
	}

	// ------------------------------ Completion ------------------------------

	@Override
	public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
		JavaTextDocument document = documents.get(params.getTextDocument().getUri());
		return document.executeIfInMicroProfileProject((projectInfo, cancelChecker) -> {
			MicroProfileJavaCompletionParams javaParams = new MicroProfileJavaCompletionParams(
					params.getTextDocument().getUri(), params.getPosition());

			CompletableFuture<CompletionList> javaParticipantCompletionsFuture = CompletableFuture
					.completedFuture(new CompletionList(new ArrayList<>()));
			ExtendedCompletionCapabilities extendedCompletionCapabilities = this.microprofileLanguageServer
					.getCapabilityManager().getClientCapabilities().getExtendedCapabilities().getCompletion();
			if (!extendedCompletionCapabilities.isSkipSendingJavaCompletionThroughLanguageServer()) {
				javaParticipantCompletionsFuture = microprofileLanguageServer.getLanguageClient()
						.getJavaCompletion(javaParams);
			}

			// Returns java snippets
			Integer completionOffset = null;
			try {
				completionOffset = document.offsetAt(params.getPosition());
			} catch (BadLocationException e) {
				LOGGER.log(Level.SEVERE, "Error while getting java snippet completions", e);
				return null;
			}
			boolean canSupportMarkdown = true;
			boolean snippetsSupported = sharedSettings.getCompletionCapabilities()
					.isCompletionSnippetsSupported();
			CompletionList list1 = new CompletionList();
			list1.setItems(new ArrayList<>());
			documents.getSnippetRegistry().getCompletionItems(document, completionOffset, canSupportMarkdown,
					snippetsSupported, (context, model) -> {
						if (context != null && context instanceof SnippetContextForJava) {
							return ((SnippetContextForJava) context).isMatch(projectInfo);
						}
						return true;
					}).forEach(item -> {
						list1.getItems().add(item);
					});

			cancelChecker.checkCanceled();

			return javaParticipantCompletionsFuture.thenApply((list2) -> {
				cancelChecker.checkCanceled();
				for (CompletionItem item : list2.getItems()) {
					list1.getItems().add(item);
				}
				// This reduces the number of completion requests to the server
				// see
				// https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocument_completion
				list1.setIsIncomplete(false);
				return Either.forRight(list1);
			});

		}, Either.forLeft(Collections.emptyList()));
	}

	// ------------------------------ Code Lens ------------------------------

	public void updateCodeLensSettings(MicroProfileCodeLensSettings newCodeLens) {
		sharedSettings.getCodeLensSettings().setUrlCodeLensEnabled(newCodeLens.isUrlCodeLensEnabled());
	}

	@Override
	public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
		boolean urlCodeLensEnabled = sharedSettings.getCodeLensSettings().isUrlCodeLensEnabled();
		if (!urlCodeLensEnabled) {
			// Don't consume JDT LS extension if all code lens are disabled.
			return CompletableFuture.completedFuture(Collections.emptyList());
		}
		JavaTextDocument document = documents.get(params.getTextDocument().getUri());
		return document.executeIfInMicroProfileProject((projectInfo, cancelChecker) -> {
			MicroProfileJavaCodeLensParams javaParams = new MicroProfileJavaCodeLensParams(
					params.getTextDocument().getUri());
			if (sharedSettings.getCommandCapabilities().isCommandSupported(CommandKind.COMMAND_OPEN_URI)) {
				javaParams.setOpenURICommand(CommandKind.COMMAND_OPEN_URI);
			}
			javaParams.setCheckServerAvailable(true);
			javaParams.setUrlCodeLensEnabled(urlCodeLensEnabled);
			// javaParams.setLocalServerPort(8080); // TODO : manage this server port from
			// the settings
			return microprofileLanguageServer.getLanguageClient().getJavaCodelens(javaParams);
		}, Collections.emptyList());
	}

	// ------------------------------ Code Action ------------------------------

	@Override
	public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
		if (validatorDelayer.isRevalidating(params.getTextDocument().getUri())) {
			return CompletableFuture.completedFuture((List<Either<Command, CodeAction>>) Collections.EMPTY_LIST);
		}
		JavaTextDocument document = documents.get(params.getTextDocument().getUri());
		return document.executeIfInMicroProfileProject((projectInfo, cancelChecker) -> {
			boolean commandConfigurationUpdateSupported = sharedSettings.getCommandCapabilities()
					.isCommandSupported(CommandKind.COMMAND_CONFIGURATION_UPDATE);
			MicroProfileJavaCodeActionParams javaParams = new MicroProfileJavaCodeActionParams();
			javaParams.setTextDocument(params.getTextDocument());
			javaParams.setRange(params.getRange());
			javaParams.setContext(params.getContext());
			javaParams.setResourceOperationSupported(microprofileLanguageServer.getCapabilityManager()
					.getClientCapabilities().isResourceOperationSupported());
			javaParams.setCommandConfigurationUpdateSupported(commandConfigurationUpdateSupported);
			return microprofileLanguageServer.getLanguageClient().getJavaCodeAction(javaParams) //
					.thenApply(codeActions -> {
						cancelChecker.checkCanceled();
						return codeActions.stream() //
								.map(ca -> {
									Either<Command, CodeAction> e = Either.forRight(ca);
									return e;
								}) //
								.collect(Collectors.toList());
					});
		}, Collections.emptyList());
	}

	// ------------------------------ Definition ------------------------------

	@Override
	public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
			DefinitionParams params) {
		JavaTextDocument document = documents.get(params.getTextDocument().getUri());
		return document.executeIfInMicroProfileProject((projectinfo, cancelChecker) -> {
			MicroProfileJavaDefinitionParams javaParams = new MicroProfileJavaDefinitionParams(
					params.getTextDocument().getUri(), params.getPosition());
			return microprofileLanguageServer.getLanguageClient().getJavaDefinition(javaParams)
					.thenApply(definitions -> {
						cancelChecker.checkCanceled();
						List<LocationLink> locations = definitions.stream() //
								.filter(definition -> definition.getLocation() != null) //
								.map(definition -> {
									LocationLink location = definition.getLocation();
									String propertyName = definition.getSelectPropertyName();
									if (propertyName != null) {
										Range targetRange = null;
										// The target range must be resolved
										String documentURI = location.getTargetUri();
										if (documentURI.endsWith(".properties")) {
											PropertiesModel model = propertiesModelProvider
													.getPropertiesModel(documentURI);
											if (model == null) {
												model = PropertiesFileUtils.loadProperties(documentURI);
											}
											if (model != null) {
												for (Node node : model.getChildren()) {
													if (node.getNodeType() == Node.NodeType.PROPERTY) {
														Property property = (Property) node;
														if (propertyName
																.equals(property.getPropertyNameWithProfile())) {
															targetRange = PositionUtils.createRange(property.getKey());
														}
													}
												}
											}
										}
										if (targetRange == null) {
											targetRange = new Range(new Position(0, 0), new Position(0, 0));
										}
										location.setTargetRange(targetRange);
										location.setTargetSelectionRange(targetRange);
									}
									return location;
								}).collect(Collectors.toList());
						if (isDefinitionLinkSupport()) {
							// I don't understand
							// return Either.forRight(locations);
						}
						return Either.forLeft(locations.stream() //
								.map((link) -> {
									return new Location(link.getTargetUri(), link.getTargetRange());
								}).collect(Collectors.toList()));
					});
		}, null);
	}

	// ------------------------------ Hover ------------------------------

	@Override
	public CompletableFuture<Hover> hover(HoverParams params) {
		JavaTextDocument document = documents.get(params.getTextDocument().getUri());
		return document.executeIfInMicroProfileProject((projectinfo, cancelChecker) -> {
			boolean markdownSupported = sharedSettings.getHoverSettings().isContentFormatSupported(MarkupKind.MARKDOWN);
			boolean surroundEqualsWithSpaces = sharedSettings.getFormattingSettings().isSurroundEqualsWithSpaces();
			DocumentFormat documentFormat = markdownSupported ? DocumentFormat.Markdown : DocumentFormat.PlainText;
			MicroProfileJavaHoverParams javaParams = new MicroProfileJavaHoverParams(params.getTextDocument().getUri(),
					params.getPosition(), documentFormat, surroundEqualsWithSpaces);
			return microprofileLanguageServer.getLanguageClient().getJavaHover(javaParams);
		}, null);
	}

	// ------------------------------ Diagnostics ------------------------------

	private void validate(JavaTextDocument javaTextDocument, boolean delay) {
		if (delay) {
			validatorDelayer.validateWithDelay(javaTextDocument);
		} else {
			triggerValidationFor(javaTextDocument);
		}
	}

	/**
	 * Validate the given opened Java file.
	 *
	 * @param document the opened Java file.
	 */
	private void triggerValidationFor(JavaTextDocument document) {
		document.executeIfInMicroProfileProject((projectinfo, cancelChecker) -> {
			String uri = document.getUri();
			triggerValidationFor(Arrays.asList(uri));
			return null;
		}, null);
	}

	/**
	 * Validate all opened Java files which belong to a MicroProfile project.
	 *
	 * @param projectURIs list of project URIs filter and null otherwise.
	 */
	private void triggerValidationForAll(Set<String> projectURIs) {
		triggerValidationFor(documents.all().stream() //
				.filter(document -> projectURIs == null || projectURIs.contains(document.getProjectURI())) //
				.map(TextDocument::getUri) //
				.collect(Collectors.toList()));
	}

	/**
	 * Validate all given Java files uris.
	 *
	 * @param uris Java files uris to validate.
	 */
	private void triggerValidationFor(List<String> uris) {
		if (uris.isEmpty()) {
			return;
		}
		List<String> excludedUnassignedProperties = sharedSettings.getValidationSettings().getUnassigned()
				.getExcluded();
		MicroProfileJavaDiagnosticsParams javaParams = new MicroProfileJavaDiagnosticsParams(uris,
				new MicroProfileJavaDiagnosticsSettings(excludedUnassignedProperties));
		boolean markdownSupported = sharedSettings.getHoverSettings().isContentFormatSupported(MarkupKind.MARKDOWN);
		if (markdownSupported) {
			javaParams.setDocumentFormat(DocumentFormat.Markdown);
		}
		microprofileLanguageServer.getLanguageClient().getJavaDiagnostics(javaParams) //
				.thenApply(diagnostics -> {
					if (diagnostics == null) {
						return null;
					}
					for (PublishDiagnosticsParams diagnostic : diagnostics) {
						microprofileLanguageServer.getLanguageClient().publishDiagnostics(diagnostic);
					}
					return null;
				});
	}

	public void propertiesChanged(MicroProfilePropertiesChangeEvent event) {
		if (documents.propertiesChanged(event) || MicroProfilePropertiesScope.isOnlyConfigFiles(event.getType())) {
			// Classpath changed or some properties config files (ex :
			// microprofile-config.properties) has been
			// saved, revalidate all opened java files.
			triggerValidationForAll(null);
		}
	}

	public void updateValidationSettings(MicroProfileValidationSettings newValidation) {
		// Update validation settings
		MicroProfileValidationSettings validation = sharedSettings.getValidationSettings();
		validation.update(newValidation);
		// trigger validation for all opened application.properties
		documents.all().stream().forEach(document -> {
			triggerValidationFor(document);
		});
	}

}
