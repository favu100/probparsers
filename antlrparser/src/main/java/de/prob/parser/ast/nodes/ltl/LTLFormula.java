package de.prob.parser.ast.nodes.ltl;

import java.util.List;

import de.prob.parser.ast.nodes.DeclarationNode;
import de.prob.parser.ast.nodes.Node;
import de.prob.parser.ast.nodes.NodeUtil;

public class LTLFormula implements Node {

    private List<DeclarationNode> implicitDeclarations;
    private LTLNode ltlNode;
    private String name;

    public void setImplicitDeclarations(List<DeclarationNode> implicitDeclarations) {
        this.implicitDeclarations = implicitDeclarations;
    }

    public void setFormula(LTLNode ltlNode) {
        this.ltlNode = ltlNode;
    }

    public LTLNode getLTLNode() {
        return ltlNode;
    }

    public List<DeclarationNode> getImplicitDeclarations() {
        return implicitDeclarations;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equalAst(Node other) {
        if (!NodeUtil.isSameClass(this, other)) {
            return false;
        }

        LTLFormula that = (LTLFormula) other;
        return this.ltlNode.equalAst(that.ltlNode)
            && NodeUtil.equalAst(this.implicitDeclarations, that.implicitDeclarations);
    }
}
